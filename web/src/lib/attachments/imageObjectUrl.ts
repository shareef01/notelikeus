/**
 * An object URL for a picture's bytes, for an `<img src>`.
 *
 * `URL.createObjectURL` always returns `blob:<origin>/<uuid>`, which `encodeURI` leaves unchanged.
 * The call is there so code scanning can see what the browser already guarantees: the URL is made
 * by the browser, never from the bytes, so it cannot carry markup or a `javascript:` scheme into
 * the attribute however the bytes were chosen.
 */
export function createImageObjectUrl(blob: Blob): string {
  return encodeURI(URL.createObjectURL(blob));
}
