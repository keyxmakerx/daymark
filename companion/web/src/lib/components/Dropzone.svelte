<script lang="ts">
  /* Drag-in / pick a backup JSON. Reads it locally; nothing is uploaded. */
  let { onload, onerror }: { onload: (text: string, name: string) => void; onerror: (msg: string) => void } = $props()

  let dragging = $state(false)
  let input: HTMLInputElement | undefined = $state()

  function handleFiles(files: FileList | null | undefined) {
    const file = files?.[0]
    if (!file) return
    if (file.size > 64 * 1024 * 1024) {
      onerror('That file is unexpectedly large for a Daymark backup (over 64 MiB).')
      return
    }
    const reader = new FileReader()
    reader.onload = () => onload(String(reader.result ?? ''), file.name)
    reader.onerror = () => onerror('Could not read that file.')
    reader.readAsText(file)
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    dragging = false
    handleFiles(e.dataTransfer?.files)
  }
</script>

<div
  class="dropzone {dragging ? 'dragging' : ''}"
  role="button"
  tabindex="0"
  aria-label="Choose or drop a Daymark backup JSON file"
  ondragover={(e) => {
    e.preventDefault()
    dragging = true
  }}
  ondragleave={() => (dragging = false)}
  ondrop={onDrop}
  onclick={() => input?.click()}
  onkeydown={(e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      input?.click()
    }
  }}
>
  <p class="big">Drop your Daymark backup here</p>
  <p class="muted">or <span class="link-like">choose a file</span> — it stays on this device</p>
  <input
    bind:this={input}
    type="file"
    accept="application/json,.json"
    class="visually-hidden"
    onchange={(e) => handleFiles((e.currentTarget as HTMLInputElement).files)}
  />
</div>

<style>
  .dropzone {
    border: 2px dashed var(--border-strong);
    border-radius: var(--radius);
    background: var(--paper-sheet);
    padding: var(--space-8) var(--space-5);
    text-align: center;
    transition: border-color 120ms ease, background 120ms ease;
  }
  .dropzone:hover,
  .dropzone.dragging { border-color: var(--focus-ring); background: var(--mood-5-wash); }
  .big { font-family: var(--font-display); font-size: 1.25rem; margin: 0 0 var(--space-2); }
  .link-like { color: var(--link); text-decoration: underline; }
</style>
