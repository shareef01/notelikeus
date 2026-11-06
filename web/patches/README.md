# Build dependency security fixes

Workbox 7.4.1 uses glob 11. The scoped overrides in package.json select glob
14.0.2 and minimatch 11.0.1 to address GHSA-3g3m-2fjr-6pw4 and
GHSA-g3hv-75wg-m249. Keep the overrides until Workbox upgrades its dependencies.
The production build and offline browser test exercise Workbox's file discovery
and generated service worker with these versions.

deepmerge 4.3.1 has no published fix for GHSA-gcqx-w48m-w2h4. The patch rejects
`__proto__` in both target copying and source merging, including recursive calls,
in the source and both distributed bundles. Other merge behavior is unchanged.
`patch-package --error-on-fail` applies it after installation; npm test exercises
the attack cases and normal Rollup option merging. Do not install with
`--ignore-scripts` when building this app.

The package version remains 4.3.1: npm audit will continue reporting deepmerge
and its parent packages despite the local fix. This is not an upstream release
and does not justify a blanket audit suppression. Remove the patch and
patch-package when an upstream fixed version is available, after rerunning the
regressions. Advisory: https://github.com/advisories/GHSA-gcqx-w48m-w2h4.
