/** Entry point of `captcha.html`, the native apps' Turnstile challenge page (D30). */
import { captchaReturnUrl, parseCaptchaReturn } from '@/lib/auth/captchaReturn';
import { loadTurnstile, turnstileSiteKey } from '@/lib/auth/turnstile';

const status = document.getElementById('captcha-status');
const widget = document.getElementById('captcha-widget');

function say(message: string) {
  if (status) status.textContent = message;
}

const target = parseCaptchaReturn(window.location.search);
const siteKey = turnstileSiteKey();

if (!target) {
  say('This page is opened by the Notelikeus app during sign-in. Go back to the app and try again.');
} else if (!siteKey || !widget) {
  say('The security check is not set up on this site.');
} else {
  loadTurnstile()
    .then((turnstile) => {
      turnstile.render(widget, {
        sitekey: siteKey,
        action: 'email-code',
        theme: 'auto',
        callback: (token) => {
          say('Done. Returning to Notelikeus…');
          window.location.replace(captchaReturnUrl(target, token));
        },
        'error-callback': () => say('The check failed. Reload the page to try again.'),
        'expired-callback': () => say('The check expired. Reload the page to try again.'),
      });
    })
    .catch(() => say('The security check could not load. Check your connection and try again.'));
}
