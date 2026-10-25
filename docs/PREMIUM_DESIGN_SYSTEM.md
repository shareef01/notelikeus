# Notelikeus — Premium Design System: Quiet Precision

**Version:** 2.4.0  
**Status:** Canonical Design Token Specification  
**Inspiration:** Interaction fidelity of Linear and Craft; quiet personal productivity; neutral chrome with high note visibility.  
**Platforms:** Web (Tailwind v4 CSS variables), Android & Windows (Jetpack Compose Multiplatform).  

---

## 1. Principles of Quiet Precision

1. **The notes are the interface.** Chrome is neutral, calm, and receding; color belongs to user notes and actionable system states.
2. **Foreground is decoupled from brand accent.** Selecting an accent color tints interactive controls (buttons, active tabs, focus rings, selected badges) — it never turns neutral reading typography into colored text.
3. **Restrained tactile elevation.** No heavy 15–20px drop shadows or decorative glassmorphic blurs behind text. Surfaces use subtle 1px crisp borders, 2dp resting elevation, and 3dp hover elevation.
4. **Instant, predictable interaction.** Control feedback responds in 100–150ms; hover transitions in 140–180ms. Reduced motion preference is respected across all platforms.
5. **Universal accessibility.** WCAG 2.2 AA contrast (≥ 4.5:1 text, ≥ 3:1 graphical elements/hit targets) across all 7 themes (Light, Dark, OLED/AMOLED, Midnight, Forest, and their OLED variants).

---

## 2. Color System & Semantic Tokens

### 2.1 Theme Neutrals (Contracts-Compliant)
The 7 official themes are defined in `contracts/design/theme-colors.json` and mirrored in Compose's `colorSchemeFor` and Web's `globals.css`:

| Role | Light | Dark | AMOLED / True Black | Midnight (Dark Blue) | Forest (Dark Green) |
|---|---|---|---|---|---|
| **App Canvas (`background`)** | `#F4F4F2` | `#101010` | `#000000` | `#070A12` | `#090E0A` |
| **Main Surface (`surface`)** | `#FFFFFF` | `#1B1B1B` | `#000000` | `#0C111C` | `#0F1610` |
| **Elevated Surface (`surfaceVariant`)** | `#ECECEA` | `#272727` | `#121212` | `#141B2A` | `#182119` |
| **Text Primary (`onSurface` / `text-primary`)** | `#151515` | `#EDEDED` | `#FFFFFF` | `#DCE8FF` | `#D6F0D6` |
| **Text Secondary (`secondary` / `text-secondary`)** | `#4D4D4B` | `#B0B0B0` | `#A8A8A8` | `#9AACCC` | `#9AB89E` |
| **Text Muted (`muted` / `text-muted`)** | `#666663` | `#949494` | `#999999` | `#8A9CB8` | `#8AA48E` |
| **Borders & Dividers (`outline`)** | `#D6D6D3` | `#323232` | `#262626` | `#283448` | `#2A382C` |

### 2.2 Interactive Brand Accents
Accent colors tint buttons, focus outlines, selection rings, and active indicators:
- **Neutral Accent (Default):**
  - Light: `#151515` (Solid black button / subtle gray hover)
  - Dark: `#EDEDED` (Crisp off-white / subtle wash)
- **Blue Accent:**
  - Light: `#0B57D0` (Subtle tint: `rgb(11 87 208 / 0.12)`)
  - Dark: `#8581FF` / `#A8C7FA`
- **Green Accent:**
  - Light: `#1B6B2E` (Subtle tint: `rgb(27 107 46 / 0.12)`)
  - Dark: `#7FD08F`

### 2.3 Semantic Status Tokens
Status is always conveyed with both color, icon, and textual descriptor:
- **`success` (Synced):** Light `#1B6B2E`, Dark `#7FD08F`
- **`info` (Syncing / In flight):** Light `#0B57D0`, Dark `#A8C7FA`
- **`caution` (Conflict copy / Offline edits pending):** Light `#9A5B00` (Wash: `#F59E0B` @ 15%), Dark `#F5B544`
- **`danger` (Save failed / Sync failed / Destructive action):** Light `#B3261E`, Dark `#F2B8B5`

---

## 3. Typography & Hierarchy

Font Family: `Inter` (`@fontsource/inter`), with system fallbacks `-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif`.

| Type Role | Font Size | Line Height | Weight | Letter Spacing | Purpose |
|---|---|---|---|---|---|
| **Display / Screen Title** | 22px / 1.375rem | 28px | 600 (Semibold) | -0.025em | Main screen headers, Labels screen title |
| **Editor Title** | 24px / 1.5rem (26px on sm+) | 32px | 600 (Semibold) | -0.03em | Note editor title input |
| **Card Title** | 18px / 1.125rem | 26px | 600 (Semibold) | -0.02em | NoteCard title (`line-clamp-2` or `3`) |
| **Body / Editor Content** | 16px / 1rem (17px on sm+) | 26px | 400 (Regular) | 0.005em | Note body writing canvas, rich text preview |
| **Card Body Preview** | 15px / 0.9375rem | 24px | 400 (Regular) | 0.005em | NoteCard preview text |
| **Navigation & Button Text** | 14px / 0.875rem | 20px | 500 (Medium) | -0.01em | Sidebar items, TopBar buttons, Dialog buttons |
| **Section Label / Overline** | 11px / 0.6875rem | 16px | 600 (Semibold) | 0.08em uppercase | Drawer headers, filter section labels |
| **Caption / Timestamp** | 11px / 0.6875rem | 14px | 500 (Medium) | 0.01em tabular | NoteCard timestamps, status badges |

---

## 4. Spacing, Geometry & Shadows

### 4.1 4px Spacing Grid
- `space-1`: 4px
- `space-2`: 8px
- `space-3`: 12px
- `space-4`: 16px
- `space-5`: 20px
- `space-6`: 24px
- `space-8`: 32px
- `gap-note`: 14px (Card gap in multi-column masonry)
- `gap-layout`: 20px (Global layout gutter)

### 4.2 Corner Radii
- `rounded-sm`: 6px (Badges, tooltips, small chips)
- `rounded-md`: 10px (Small buttons, inputs, segmented controls)
- `rounded-lg`: 14px (Dialog inner sections, floating menus)
- `rounded-note`: 18px (Note cards, dialogs, drawers)
- `rounded-full`: 9999px (Pills, filter chips, icon buttons)

### 4.3 Tactile Elevation
- **Card Resting (2dp):** `0 1px 3px 0 rgb(0 0 0 / 0.08), 0 1px 2px -1px rgb(0 0 0 / 0.08)`
- **Card Hover (3dp):** `0 3px 8px -1px rgb(0 0 0 / 0.09), 0 1px 4px -1px rgb(0 0 0 / 0.06)`
- **Overlay / Modal (6dp):** `0 12px 28px -4px rgb(0 0 0 / 0.22), 0 4px 10px -2px rgb(0 0 0 / 0.12)`
- **Header Scrolled Divider:** `0 1px 0 0 rgb(var(--outline-rgb) / 0.35)`

---

## 5. Component Primitives Specification

### 5.1 Button Primitive
- **Variants:**
  - `primary`: Background `brand-primary`, text `true-surface`, subtle scale on press (`active:scale-[0.98]`).
  - `secondary`: Border `1px outline`, background `surfaceVariant/40`, hover `surfaceVariant/80`.
  - `ghost`: Transparent background, hover `brand-primary/8%`.
  - `danger`: Red border & text, hover `red-500/10%`.
- **Target Sizes:**
  - Small (`sm`): min-h 32px (pointer)
  - Medium (`md`): min-h 40px (default)
  - Large (`lg`): min-h 48px (touch-friendly)

### 5.2 Badge Primitive
- **Semantic Variants:**
  - `neutral`: `bg-brand-outline/25 text-brand-muted`
  - `accent`: `bg-brand-primary/20 text-brand-primary`
  - `caution`: `bg-amber-500/15 text-amber-800 dark:text-amber-400 border border-amber-500/40`
  - `danger`: `bg-red-500/15 text-red-800 dark:text-red-400 border border-red-500/40`
  - `success`: `bg-emerald-500/15 text-emerald-800 dark:text-emerald-400 border border-emerald-500/40`
- **Typography:** Tabular numerals with compact micro-pill density. Used for note counts and conflict warnings.

### 5.3 EmptyState Primitive
- **Design:** Centered quiet layout with 25% opacity icon, clear headline, explanatory subtitle, and optional primary call to action. Used across scopes and labels screen.

### 5.4 RichTextToolbar Composite
- **W3C ARIA APG Toolbar Pattern:**
  - Single Tab stop in page order when navigating with keyboard.
  - ArrowLeft / ArrowRight / ArrowUp / ArrowDown to cycle through formatting buttons.
  - Home / End to jump to first / last formatting action.
  - Active button carries `tabIndex={0}`, non-active buttons carry `tabIndex={-1}`.
  - Focus is restored directly to note body upon formatting activation.

---

## 6. Motion & Accessibility

- **Durations:**
  - Buttons / Chips: 100–140ms
  - Dialog / Sheet enter: 180–220ms
  - Focus transitions: 100ms
- **Reduced Motion:**
  - `prefers-reduced-motion: reduce` sets transitions to 0.01ms and removes transforms.
- **Focus Rings:**
  - 2px accent ring with 2px offset (`CHROME_FOCUS`).
  - On note surfaces: `focus-visible:outline-current`.
