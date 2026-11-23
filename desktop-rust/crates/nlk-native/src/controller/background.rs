//! Work that must not freeze the window: syncing, signing in, waiting for the browser's security check.
//!
//! A job runs on a worker thread and its result comes back to the window's thread through
//! `slint::invoke_from_event_loop` and the window's `background-done` callback. A job that needs the
//! notes holds them for as long as it runs; meanwhile what the window asks for (a click, an autosave)
//! waits in a queue and runs, in order, when the job is done. Typing never waits: the editor keeps the
//! words, and the autosave that follows simply comes a moment later.

use std::any::Any;
use std::cell::{Cell, RefCell};
use std::collections::{HashMap, VecDeque};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex};

use notelikeus_app::NotesService;

use super::Controller;

const MAX_CONCURRENT_BG_WORKERS: usize = 4;

/// Where jobs run.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// On a worker thread: the app.
    Threads,
    /// On the calling thread, at once: headless tests, which have no event loop to hand results back on.
    Inline,
}

type Deferred = Box<dyn FnOnce(&Controller)>;
type AnyResult = Result<Box<dyn Any + Send>, ()>;
type Done = Box<dyn FnOnce(&Controller, AnyResult)>;

pub(super) struct Jobs {
    mode: Mode,
    next_id: Cell<u64>,
    /// Running jobs that hold the notes.
    holding: Cell<usize>,
    /// Running bounded background jobs (pictures) that do not hold the notes.
    active_bg: Cell<usize>,
    pending_bg: RefCell<VecDeque<Deferred>>,
    deferred: RefCell<Vec<Deferred>>,
    /// What to do with each running job's result, and whether it holds the notes.
    done: RefCell<HashMap<u64, (bool, bool, Done)>>,
    /// Results handed back by workers, waiting for the window's thread.
    finished: Arc<Mutex<Vec<(u64, AnyResult)>>>,
}

impl Jobs {
    pub(super) fn new(mode: Mode) -> Jobs {
        Jobs {
            mode,
            next_id: Cell::new(0),
            holding: Cell::new(0),
            active_bg: Cell::new(0),
            pending_bg: RefCell::default(),
            deferred: RefCell::default(),
            done: RefCell::default(),
            finished: Arc::default(),
        }
    }

    /// Whether a worker has the notes right now.
    pub(super) fn holds_notes(&self) -> bool {
        self.holding.get() > 0
    }

    /// Runs `action` once no worker holds the notes.
    pub(super) fn defer(&self, action: Deferred) {
        self.deferred.borrow_mut().push(action);
    }
}

impl Controller {
    /// Runs `job` with the notes, off the window's thread; `done` gets its result on the window's thread.
    pub(super) fn with_notes_in_background<R: Send + 'static>(
        &self,
        job: impl FnOnce(&mut NotesService) -> R + Send + 'static,
        done: impl FnOnce(&Controller, R) + 'static,
    ) {
        if self.jobs.holds_notes() {
            // One job holds the notes at a time; this one goes after it.
            self.jobs.defer(Box::new(move |c| c.with_notes_in_background(job, done)));
            return;
        }
        let service = self.service.clone();
        self.spawn(
            true,
            false,
            move || {
                let mut notes = service.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
                job(&mut notes)
            },
            done,
        );
    }

    /// Runs bounded `job` (e.g. picture decode/download) off the window's thread.
    pub(super) fn in_background<R: Send + 'static>(
        &self,
        job: impl FnOnce() -> R + Send + 'static,
        done: impl FnOnce(&Controller, R) + 'static,
    ) {
        if self.jobs.mode == Mode::Threads && self.jobs.active_bg.get() >= MAX_CONCURRENT_BG_WORKERS {
            self.jobs.pending_bg.borrow_mut().push_back(Box::new(move |c| c.in_background(job, done)));
            return;
        }
        self.spawn(false, self.jobs.mode == Mode::Threads, job, done);
    }

    /// Runs an interactive wait (such as browser captcha return) immediately without queueing behind pictures.
    pub(super) fn in_background_unbounded<R: Send + 'static>(
        &self,
        job: impl FnOnce() -> R + Send + 'static,
        done: impl FnOnce(&Controller, R) + 'static,
    ) {
        self.spawn(false, false, job, done);
    }

    fn spawn<R: Send + 'static>(
        &self,
        holds_notes: bool,
        bounded_bg: bool,
        job: impl FnOnce() -> R + Send + 'static,
        done: impl FnOnce(&Controller, R) + 'static,
    ) {
        let id = self.jobs.next_id.get();
        self.jobs.next_id.set(id + 1);
        let done: Done = Box::new(move |c, outcome| match outcome {
            Ok(result) => {
                if let Ok(result) = result.downcast::<R>() {
                    done(c, *result);
                }
            }
            Err(()) => c.recover_from_worker_panic(holds_notes),
        });
        self.jobs.done.borrow_mut().insert(id, (holds_notes, bounded_bg, done));
        if holds_notes {
            self.jobs.holding.set(self.jobs.holding.get() + 1);
        }
        if bounded_bg {
            self.jobs.active_bg.set(self.jobs.active_bg.get() + 1);
        }
        let finished = self.jobs.finished.clone();
        match self.jobs.mode {
            Mode::Inline => {
                let outcome = match catch_unwind(AssertUnwindSafe(job)) {
                    Ok(value) => Ok(Box::new(value) as Box<dyn Any + Send>),
                    Err(_) => Err(()),
                };
                finished.lock().unwrap_or_else(|p| p.into_inner()).push((id, outcome));
                self.background_done();
            }
            Mode::Threads => {
                let ui = self.ui.clone();
                std::thread::spawn(move || {
                    let outcome = match catch_unwind(AssertUnwindSafe(job)) {
                        Ok(value) => Ok(Box::new(value) as Box<dyn Any + Send>),
                        Err(_) => Err(()),
                    };
                    finished.lock().unwrap_or_else(|p| p.into_inner()).push((id, outcome));
                    // Fails only when the window is gone, and then nobody is waiting.
                    let _ = slint::invoke_from_event_loop(move || {
                        if let Some(ui) = ui.upgrade() {
                            ui.invoke_background_done();
                        }
                    });
                });
            }
        }
    }

    fn recover_from_worker_panic(&self, held_notes: bool) {
        if held_notes {
            self.account.reset_after_deletion();
            if let Some(ui) = self.ui() {
                ui.set_signin_busy(false);
                ui.set_deleting_account(false);
            }
            self.show_account();
        }
        self.show_toast("An unexpected error interrupted that action. Try again.", None, None);
    }

    /// Hands each finished job's result to its `done`, then runs what waited for the notes or slots.
    pub(super) fn background_done(&self) {
        let finished = std::mem::take(&mut *self.jobs.finished.lock().unwrap_or_else(|p| p.into_inner()));
        for (id, result) in finished {
            let Some((held_notes, bounded_bg, done)) = self.jobs.done.borrow_mut().remove(&id) else { continue };
            if held_notes {
                self.jobs.holding.set(self.jobs.holding.get().saturating_sub(1));
            }
            if bounded_bg {
                self.jobs.active_bg.set(self.jobs.active_bg.get().saturating_sub(1));
            }
            done(self, result);
        }
        while self.jobs.active_bg.get() < MAX_CONCURRENT_BG_WORKERS {
            let next = self.jobs.pending_bg.borrow_mut().pop_front();
            let Some(next) = next else { break };
            next(self);
        }
        // In order, and only while no new job has taken the notes.
        while !self.jobs.holds_notes() {
            let next = {
                let mut queue = self.jobs.deferred.borrow_mut();
                if queue.is_empty() {
                    break;
                }
                queue.remove(0)
            };
            next(self);
        }
    }
}
