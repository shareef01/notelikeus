# Play Console — quick reference

Copy these values into Google Play Console when creating the listing.

| Field | Value |
|-------|--------|
| **App name** | See `listing/en-US/title.txt` |
| **Package name** | `com.aus.notelikeus` |
| **Category** | Productivity |
| **Privacy policy URL** | https://notelike.web.app/privacy.html |
| **Default language** | English (United States) |

## Listing text

- Short description: `listing/en-US/short_description.txt`
- Full description: `listing/en-US/full_description.txt`
- Release notes: `listing/en-US/whats_new.txt`

## Graphics

- App icon (512×512): `store/ic_launcher_512.png`
- Feature graphic (1024×500): create and upload manually
- Phone screenshots: capture light + dark theme (minimum 2)

## Data safety

Complete the form using `DATA_SAFETY.md`. No ads, no analytics SDKs, optional cloud sync via Firebase.

## Release build

```powershell
.\scripts\bundle-release.ps1
```

Upload `app/build/outputs/bundle/release/app-release.aab` to Internal testing first.
