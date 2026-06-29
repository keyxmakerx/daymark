import { vitePreprocess } from '@sveltejs/vite-plugin-svelte'

export default {
  // Svelte 5; TS preprocessing for <script lang="ts">.
  preprocess: vitePreprocess(),
}
