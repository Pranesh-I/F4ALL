import "@testing-library/jest-dom/vitest";

// The dashboard uses a data router (createBrowserRouter) so pages can block
// navigation with unsaved input. Its navigations build a fetch Request with an
// AbortSignal; under test that signal is jsdom's, which Node's own Request
// refuses. Browsers are unaffected. Nothing here uses loaders or actions, so
// the signal is simply not passed on in tests.
const NodeRequest = globalThis.Request;
globalThis.Request = class extends NodeRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    if (init?.signal) {
      const { signal: _signal, ...rest } = init;
      super(input, rest);
    } else {
      super(input, init);
    }
  }
} as typeof Request;
