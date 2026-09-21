import { useSyncExternalStore } from 'react';
import { useColorScheme as useRNColorScheme } from 'react-native';

/**
 * Static rendering hands the browser HTML built on the server, where there is no color scheme to
 * read. Returning the real scheme on that first render would make the client's markup disagree
 * with the server's, so 'light' is reported until hydration finishes.
 *
 * `useSyncExternalStore` is what expresses that: its third argument is the server snapshot, so
 * React itself supplies `false` while rendering on the server and `true` once hydrated. The
 * earlier `useState` + `useEffect` version did the same thing by setting state inside an effect,
 * which costs an extra render pass and is exactly the cascade React Compiler warns about.
 */

/** Nothing to subscribe to: hydration happens once and never reverts. */
const subscribe = () => () => {};

export function useColorScheme() {
  const hasHydrated = useSyncExternalStore(
    subscribe,
    () => true,
    () => false
  );

  const colorScheme = useRNColorScheme();

  return hasHydrated ? colorScheme : 'light';
}
