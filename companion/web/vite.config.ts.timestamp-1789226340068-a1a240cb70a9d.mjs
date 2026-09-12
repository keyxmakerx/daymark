// vite.config.ts
import { defineConfig } from "file:///home/user/daymark/companion/web/node_modules/vitest/dist/config.js";
import { svelte } from "file:///home/user/daymark/companion/web/node_modules/@sveltejs/vite-plugin-svelte/src/index.js";
import { fileURLToPath, URL as NodeURL } from "node:url";
var __vite_injected_original_import_meta_url = "file:///home/user/daymark/companion/web/vite.config.ts";
var sodiumCjs = fileURLToPath(new URL("node_modules/libsodium-wrappers-sumo/dist/modules-sumo/libsodium-wrappers.js", __vite_injected_original_import_meta_url));
var vite_config_default = defineConfig({
  base: "./",
  plugins: [svelte()],
  resolve: {
    alias: {
      "libsodium-wrappers-sumo": sodiumCjs
    }
  },
  build: {
    target: "es2022",
    outDir: "dist",
    emptyOutDir: true,
    assetsInlineLimit: 0,
    sourcemap: false,
    rollupOptions: {
      // Multi-page build: the owner report viewer (index.html) and the SEPARATE therapist portal
      // (therapist.html) are distinct entries/surfaces served at distinct routes.
      input: {
        index: fileURLToPath(new NodeURL("index.html", __vite_injected_original_import_meta_url)),
        therapist: fileURLToPath(new NodeURL("therapist.html", __vite_injected_original_import_meta_url)),
        // The server admin console: a third surface, for whoever operates the server rather than
        // the owner or a clinician. It serves no client data at all.
        admin: fileURLToPath(new NodeURL("admin.html", __vite_injected_original_import_meta_url)),
        // The practice console: a fourth surface, for whoever administers a practice — membership,
        // roles, removal and the practice's own control-plane log. Served by the existing static
        // handler at /practice.html; no server route was added for it.
        practice: fileURLToPath(new NodeURL("practice.html", __vite_injected_original_import_meta_url))
      },
      output: {
        entryFileNames: "assets/[name]-[hash].js",
        chunkFileNames: "assets/[name]-[hash].js",
        assetFileNames: "assets/[name]-[hash][extname]"
      }
    }
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
    testTimeout: 3e4,
    hookTimeout: 3e4
  }
});
export {
  vite_config_default as default
};
//# sourceMappingURL=data:application/json;base64,ewogICJ2ZXJzaW9uIjogMywKICAic291cmNlcyI6IFsidml0ZS5jb25maWcudHMiXSwKICAic291cmNlc0NvbnRlbnQiOiBbImNvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9kaXJuYW1lID0gXCIvaG9tZS91c2VyL2RheW1hcmsvY29tcGFuaW9uL3dlYlwiO2NvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9maWxlbmFtZSA9IFwiL2hvbWUvdXNlci9kYXltYXJrL2NvbXBhbmlvbi93ZWIvdml0ZS5jb25maWcudHNcIjtjb25zdCBfX3ZpdGVfaW5qZWN0ZWRfb3JpZ2luYWxfaW1wb3J0X21ldGFfdXJsID0gXCJmaWxlOi8vL2hvbWUvdXNlci9kYXltYXJrL2NvbXBhbmlvbi93ZWIvdml0ZS5jb25maWcudHNcIjsvLy8gPHJlZmVyZW5jZSB0eXBlcz1cInZpdGVzdC9jb25maWdcIiAvPlxuaW1wb3J0IHsgZGVmaW5lQ29uZmlnIH0gZnJvbSAndml0ZXN0L2NvbmZpZydcbmltcG9ydCB7IHN2ZWx0ZSB9IGZyb20gJ0BzdmVsdGVqcy92aXRlLXBsdWdpbi1zdmVsdGUnXG5pbXBvcnQgeyBmaWxlVVJMVG9QYXRoLCBVUkwgYXMgTm9kZVVSTCB9IGZyb20gJ25vZGU6dXJsJ1xuXG4vLyBsaWJzb2RpdW0td3JhcHBlcnMgc2hpcHMgYSBicm9rZW4gRVNNIGJ1aWxkIChpdHMgLm1qcyBpbXBvcnRzIGEgc2libGluZyB0aGF0IGlzbid0XG4vLyBwdWJsaXNoZWQpOyB0aGUgQ0pTIGJ1aWxkIGlzIGZpbmUuIEFsaWFzIHRvIGl0IHNvIGJvdGggdGhlIGJyb3dzZXIgYnVuZGxlIGFuZCB0aGVcbi8vIE5vZGUgdGVzdHMgbG9hZCBhIHdvcmtpbmcgbW9kdWxlLiBFdmVyeXRoaW5nIHN0YXlzIHZlbmRvcmVkIFx1MjAxNCBubyBDRE4uXG5jb25zdCBzb2RpdW1DanMgPSBmaWxlVVJMVG9QYXRoKG5ldyBVUkwoJ25vZGVfbW9kdWxlcy9saWJzb2RpdW0td3JhcHBlcnMtc3Vtby9kaXN0L21vZHVsZXMtc3Vtby9saWJzb2RpdW0td3JhcHBlcnMuanMnLCBpbXBvcnQubWV0YS51cmwpKVxuXG5leHBvcnQgZGVmYXVsdCBkZWZpbmVDb25maWcoe1xuICBiYXNlOiAnLi8nLFxuICBwbHVnaW5zOiBbc3ZlbHRlKCldLFxuICByZXNvbHZlOiB7XG4gICAgYWxpYXM6IHtcbiAgICAgICdsaWJzb2RpdW0td3JhcHBlcnMtc3Vtbyc6IHNvZGl1bUNqcyxcbiAgICB9LFxuICB9LFxuICBidWlsZDoge1xuICAgIHRhcmdldDogJ2VzMjAyMicsXG4gICAgb3V0RGlyOiAnZGlzdCcsXG4gICAgZW1wdHlPdXREaXI6IHRydWUsXG4gICAgYXNzZXRzSW5saW5lTGltaXQ6IDAsXG4gICAgc291cmNlbWFwOiBmYWxzZSxcbiAgICByb2xsdXBPcHRpb25zOiB7XG4gICAgICAvLyBNdWx0aS1wYWdlIGJ1aWxkOiB0aGUgb3duZXIgcmVwb3J0IHZpZXdlciAoaW5kZXguaHRtbCkgYW5kIHRoZSBTRVBBUkFURSB0aGVyYXBpc3QgcG9ydGFsXG4gICAgICAvLyAodGhlcmFwaXN0Lmh0bWwpIGFyZSBkaXN0aW5jdCBlbnRyaWVzL3N1cmZhY2VzIHNlcnZlZCBhdCBkaXN0aW5jdCByb3V0ZXMuXG4gICAgICBpbnB1dDoge1xuICAgICAgICBpbmRleDogZmlsZVVSTFRvUGF0aChuZXcgTm9kZVVSTCgnaW5kZXguaHRtbCcsIGltcG9ydC5tZXRhLnVybCkpLFxuICAgICAgICB0aGVyYXBpc3Q6IGZpbGVVUkxUb1BhdGgobmV3IE5vZGVVUkwoJ3RoZXJhcGlzdC5odG1sJywgaW1wb3J0Lm1ldGEudXJsKSksXG4gICAgICAgIC8vIFRoZSBzZXJ2ZXIgYWRtaW4gY29uc29sZTogYSB0aGlyZCBzdXJmYWNlLCBmb3Igd2hvZXZlciBvcGVyYXRlcyB0aGUgc2VydmVyIHJhdGhlciB0aGFuXG4gICAgICAgIC8vIHRoZSBvd25lciBvciBhIGNsaW5pY2lhbi4gSXQgc2VydmVzIG5vIGNsaWVudCBkYXRhIGF0IGFsbC5cbiAgICAgICAgYWRtaW46IGZpbGVVUkxUb1BhdGgobmV3IE5vZGVVUkwoJ2FkbWluLmh0bWwnLCBpbXBvcnQubWV0YS51cmwpKSxcbiAgICAgICAgLy8gVGhlIHByYWN0aWNlIGNvbnNvbGU6IGEgZm91cnRoIHN1cmZhY2UsIGZvciB3aG9ldmVyIGFkbWluaXN0ZXJzIGEgcHJhY3RpY2UgXHUyMDE0IG1lbWJlcnNoaXAsXG4gICAgICAgIC8vIHJvbGVzLCByZW1vdmFsIGFuZCB0aGUgcHJhY3RpY2UncyBvd24gY29udHJvbC1wbGFuZSBsb2cuIFNlcnZlZCBieSB0aGUgZXhpc3Rpbmcgc3RhdGljXG4gICAgICAgIC8vIGhhbmRsZXIgYXQgL3ByYWN0aWNlLmh0bWw7IG5vIHNlcnZlciByb3V0ZSB3YXMgYWRkZWQgZm9yIGl0LlxuICAgICAgICBwcmFjdGljZTogZmlsZVVSTFRvUGF0aChuZXcgTm9kZVVSTCgncHJhY3RpY2UuaHRtbCcsIGltcG9ydC5tZXRhLnVybCkpLFxuICAgICAgfSxcbiAgICAgIG91dHB1dDoge1xuICAgICAgICBlbnRyeUZpbGVOYW1lczogJ2Fzc2V0cy9bbmFtZV0tW2hhc2hdLmpzJyxcbiAgICAgICAgY2h1bmtGaWxlTmFtZXM6ICdhc3NldHMvW25hbWVdLVtoYXNoXS5qcycsXG4gICAgICAgIGFzc2V0RmlsZU5hbWVzOiAnYXNzZXRzL1tuYW1lXS1baGFzaF1bZXh0bmFtZV0nLFxuICAgICAgfSxcbiAgICB9LFxuICB9LFxuICB0ZXN0OiB7XG4gICAgZW52aXJvbm1lbnQ6ICdub2RlJyxcbiAgICBpbmNsdWRlOiBbJ3NyYy8qKi8qLnRlc3QudHMnXSxcbiAgICB0ZXN0VGltZW91dDogMzAwMDAsXG4gICAgaG9va1RpbWVvdXQ6IDMwMDAwLFxuICB9LFxufSlcbiJdLAogICJtYXBwaW5ncyI6ICI7QUFDQSxTQUFTLG9CQUFvQjtBQUM3QixTQUFTLGNBQWM7QUFDdkIsU0FBUyxlQUFlLE9BQU8sZUFBZTtBQUgwSCxJQUFNLDJDQUEyQztBQVF6TixJQUFNLFlBQVksY0FBYyxJQUFJLElBQUksZ0ZBQWdGLHdDQUFlLENBQUM7QUFFeEksSUFBTyxzQkFBUSxhQUFhO0FBQUEsRUFDMUIsTUFBTTtBQUFBLEVBQ04sU0FBUyxDQUFDLE9BQU8sQ0FBQztBQUFBLEVBQ2xCLFNBQVM7QUFBQSxJQUNQLE9BQU87QUFBQSxNQUNMLDJCQUEyQjtBQUFBLElBQzdCO0FBQUEsRUFDRjtBQUFBLEVBQ0EsT0FBTztBQUFBLElBQ0wsUUFBUTtBQUFBLElBQ1IsUUFBUTtBQUFBLElBQ1IsYUFBYTtBQUFBLElBQ2IsbUJBQW1CO0FBQUEsSUFDbkIsV0FBVztBQUFBLElBQ1gsZUFBZTtBQUFBO0FBQUE7QUFBQSxNQUdiLE9BQU87QUFBQSxRQUNMLE9BQU8sY0FBYyxJQUFJLFFBQVEsY0FBYyx3Q0FBZSxDQUFDO0FBQUEsUUFDL0QsV0FBVyxjQUFjLElBQUksUUFBUSxrQkFBa0Isd0NBQWUsQ0FBQztBQUFBO0FBQUE7QUFBQSxRQUd2RSxPQUFPLGNBQWMsSUFBSSxRQUFRLGNBQWMsd0NBQWUsQ0FBQztBQUFBO0FBQUE7QUFBQTtBQUFBLFFBSS9ELFVBQVUsY0FBYyxJQUFJLFFBQVEsaUJBQWlCLHdDQUFlLENBQUM7QUFBQSxNQUN2RTtBQUFBLE1BQ0EsUUFBUTtBQUFBLFFBQ04sZ0JBQWdCO0FBQUEsUUFDaEIsZ0JBQWdCO0FBQUEsUUFDaEIsZ0JBQWdCO0FBQUEsTUFDbEI7QUFBQSxJQUNGO0FBQUEsRUFDRjtBQUFBLEVBQ0EsTUFBTTtBQUFBLElBQ0osYUFBYTtBQUFBLElBQ2IsU0FBUyxDQUFDLGtCQUFrQjtBQUFBLElBQzVCLGFBQWE7QUFBQSxJQUNiLGFBQWE7QUFBQSxFQUNmO0FBQ0YsQ0FBQzsiLAogICJuYW1lcyI6IFtdCn0K
