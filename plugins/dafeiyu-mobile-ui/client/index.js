/* Self-contained plugin factory serialized into the generated browser entry. */
export function createDafeiyuMobileUiPlugin(core, cssText, createFooter, createStartup) {
  return {
    inject: ['layout', 'connection'],
    apply(ctx) {
      return ctx.effect(() => {
        const removeSurface = core.install(cssText, ctx.layout, {
          createFooter: createFooter ? (env, options) => createFooter(env, { ...options, connection: ctx.connection }) : undefined,
        });
        const removeStartup = createStartup?.(globalThis, { readContext: core.readContext, connection: ctx.connection });
        return () => { removeStartup?.(); removeSurface(); };
      },
        'dafeiyu-mobile-ui: explicit mobile presentation lifecycle');
    },
  };
}
