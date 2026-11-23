use notelikeus_core::http::{AccountDeletionError, AttachmentsClient, SupabaseTransport};
use notelikeus_core::store::Store;

use super::{GUEST, NotesService};

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DeleteAccountReport {
    pub local_data_removed: bool,
    pub warning: Option<String>,
}

impl NotesService {
    /// Account deletion is available only for a signed-in account with its owner-authenticated Worker.
    pub fn can_delete_account(&self) -> bool {
        self.session.is_some() && self.config.as_ref().is_some_and(|config| config.attachments_url.is_some())
    }

    /// Asks the Worker to delete the signed-in account, checks ambiguous results through Supabase, then
    /// clears this account's sealed local copy and returns to the separate guest store.
    pub fn delete_account(&mut self) -> Result<DeleteAccountReport, String> {
        let config = self.config.clone().ok_or("Sync isn't set up in this build.")?;
        let worker_url = config.attachments_url.as_deref().ok_or("Account deletion isn't available in this build.")?;
        let mut session = self.session.clone().ok_or("Sign in to delete your account.")?;
        let auth = self.auth()?;
        if session.needs_refresh(self.ids.now_millis()) {
            session = self.refreshed(&auth)?;
        }

        // Prepare the guest store before asking the Worker, so a successful server deletion can always
        // switch the running app away from the account's data.
        let guest_store = Store::open(&self.root, GUEST, self.protector.as_ref()).map_err(|error| error.to_string())?;
        let guest_state = guest_store.load().map_err(|error| error.to_string())?;
        let mut worker =
            AttachmentsClient::new(worker_url, &session.access_token).map_err(|error| error.to_string())?;
        let outcome = match worker.delete_account() {
            Err(AccountDeletionError::Unauthorized) => {
                session = self.refreshed(&auth)?;
                worker.set_access_token(&session.access_token);
                worker.delete_account()
            }
            other => other,
        };
        match outcome {
            Ok(()) => {}
            Err(AccountDeletionError::Unauthorized) => return Err("Sign in again to delete your account.".into()),
            Err(AccountDeletionError::ReauthenticationRequired) => {
                return Err(
                    "Your sign-in is too old to delete the account. Sign out and sign in again, then retry.".into()
                );
            }
            Err(AccountDeletionError::Refused(status)) => {
                return Err(format!("The server refused account deletion (HTTP {status}); nothing was deleted."));
            }
            Err(AccountDeletionError::OutcomeUnknown) => {
                let transport = SupabaseTransport::new(&config.url, &config.publishable_key, &session.access_token)
                    .map_err(|_| {
                        "Could not confirm whether the account was deleted. Check your connection.".to_string()
                    })?;
                match transport.account_is_deleted() {
                    Ok(true) => {}
                    Ok(false) => {
                        return Err("The account is still active; nothing was deleted. Try again when online.".into());
                    }
                    Err(_) => {
                        return Err("Could not confirm whether the account was deleted. Check your connection.".into());
                    }
                }
            }
        }

        let session_cleared = self.vault.clear().is_ok();
        let local_data_removed = self.store.remove_account_data().is_ok();
        self.session = None;
        self.last_synced_at = None;
        self.last_error = None;
        self.store = guest_store;
        self.state = guest_state;
        self.account_generation = self.account_generation.saturating_add(1);

        let warning = match (session_cleared, local_data_removed) {
            (false, false) => Some(
                "The online account is deleted, but this device could not clear its saved sign-in or sealed local copy."
                    .into(),
            ),
            (false, true) => Some(
                "The online account and its local notes are deleted, but this device could not clear its saved sign-in."
                    .into(),
            ),
            (true, false) => Some(
                "The online account is deleted, but this device could not remove its sealed local copy.".into(),
            ),
            (true, true) => None,
        };
        Ok(DeleteAccountReport { local_data_removed, warning })
    }
}
