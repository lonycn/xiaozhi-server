import { watch } from 'vue';

export const anonymousRouteNames = new Set(['login', 'Register', 'RetrievePassword', 'welcome']);

// A route guard protects navigation; this watcher also protects an already-open
// page when logout, an invalid DTO, or a failed refresh clears authentication.
export function watchAuthRoutes(auth, router) {
  return watch(
    [() => auth.bearer, () => router.currentRoute.value.fullPath],
    ([bearer]) => {
      const route = router.currentRoute.value;
      if (!bearer && route.matched.length && !anonymousRouteNames.has(route.name)) {
        void router.replace({ name: 'login' });
      }
    },
    { immediate: true, flush: 'sync' },
  );
}
