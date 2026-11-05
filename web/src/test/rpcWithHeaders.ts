/** Minimal PostgREST builder shape for tests whose RPC handlers return promises. */
export function rpcWithHeaders<A extends unknown[], R>(rpc: (...args: A) => R) {
  return (...args: A) => {
    const request = Promise.resolve(rpc(...args));
    return Object.assign(request, { setHeader: () => request });
  };
}
