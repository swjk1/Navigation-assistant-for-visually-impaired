/**
 * CSS is web-only here: `global.css` sets font-family custom properties that
 * `react-native-web` picks up, and `animated-icon.module.css` styles the web
 * variant of that component. Neither has a Metro loader on native, which is why
 * both are imported only from `.web.tsx` files or behind a Platform check.
 *
 * Expo's generated `expo-env.d.ts` does not declare these, so `strict` builds
 * fail on the imports without this file.
 */

declare module '*.module.css' {
  const classes: Record<string, string>;
  export default classes;
}

declare module '*.css';
