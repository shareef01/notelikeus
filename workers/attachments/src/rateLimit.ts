import type { RateLimiterBinding } from './auth';

/**
 * Worker-level throttling, when the deployment provides a rate-limit binding.
 *
 * Cloudflare evaluates these counters at the edge, not in isolate memory, so the limit is not
 * defeated by the request landing on a different isolate — which is exactly why an in-process
 * counter would be worthless here.
 *
 * Everything is optional by design. Without the binding these helpers do nothing, and the Worker
 * behaves precisely as it did before: the deployment, not the code, decides whether throttling is
 * on. Rate limiting is not the only abuse control this service needs; per-endpoint WAF rules and
 * edge protections are configured on the Cloudflare zone and are not represented in this repo.
 */

/** Cloudflare sets this on every proxied request; it is absent in local `wrangler dev` runs. */
const CLIENT_IP_HEADER = 'CF-Connecting-IP';

async function withinLimit(limiter: RateLimiterBinding | undefined, key: string): Promise<boolean> {
  if (!limiter) return true;
  try {
    const { success } = await limiter.limit({ key });
    return success;
  } catch {
    // A limiter that cannot answer must not take the API down with it. Failing open is the
    // deliberate choice: throttling is a mitigation, and the authorization boundary is elsewhere.
    return true;
  }
}

function tooManyRequests(): Response {
  return new Response('Too Many Requests', {
    status: 429,
    headers: { 'Retry-After': '60' },
  });
}

/**
 * Pre-authentication throttle, keyed by client IP.
 *
 * This is the only control standing between an anonymous flood and an outbound Supabase Auth
 * request per attempt. Requests with no client IP (local development) are not throttled.
 */
export async function rateLimitByClient(
  request: Request,
  limiter: RateLimiterBinding | undefined,
): Promise<Response | null> {
  const clientIp = request.headers.get(CLIENT_IP_HEADER)?.trim();
  if (!limiter || !clientIp) return null;
  return (await withinLimit(limiter, `ip:${clientIp}`)) ? null : tooManyRequests();
}

/** Post-authentication throttle, keyed by the authenticated user id. */
export async function rateLimitByUser(
  userId: string,
  limiter: RateLimiterBinding | undefined,
): Promise<Response | null> {
  if (!limiter) return null;
  return (await withinLimit(limiter, `user:${userId}`)) ? null : tooManyRequests();
}
