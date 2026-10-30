const assets = import.meta.glob('/src/assets/**/*', { eager: true, query: '?url', import: 'default' });

/** Explicit Vite asset catalogue preserves dynamic avatars and locale-specific artwork. */
export function assetUrl(path) {
  if (typeof path !== 'string') return '';
  const normalized = path.replace(/^@\//, '/src/');
  return assets[normalized] || (path.startsWith('/') && !path.startsWith('/src/') ? path : '');
}

export default assets;
