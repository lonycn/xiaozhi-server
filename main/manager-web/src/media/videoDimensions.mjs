// Bound captures by both longest edge and pixel count, independent of orientation.
export function captureDimensions(width, height) {
  if (!(width > 0 && height > 0)) return { width: 0, height: 0 };
  const scale = Math.min(1, 1920 / Math.max(width, height), Math.sqrt((1920 * 1080) / (width * height)));
  return { width: Math.max(1, Math.floor(width * scale)), height: Math.max(1, Math.floor(height * scale)) };
}

// The transport owns the nodes. Observe metadata without moving or replacing them.
export function observeVideoDimensions(changed) {
  const listeners = new Map();
  return {
    add(element) {
      if (!element || element.tagName !== 'VIDEO' || listeners.has(element)) return;
      const update = () => changed();
      for (const event of ['loadedmetadata', 'resize', 'emptied']) element.addEventListener(event, update);
      listeners.set(element, update);
      changed();
    },
    prune(hosts) {
      for (const [element, update] of listeners) {
        if (hosts.some(host => host?.contains(element))) continue;
        for (const event of ['loadedmetadata', 'resize', 'emptied']) element.removeEventListener(event, update);
        listeners.delete(element);
      }
    },
    disconnect() { this.prune([]); },
  };
}
