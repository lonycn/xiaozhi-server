// Leaf configuration module. Business API modules must not import the API
// aggregate, which imports those same modules and creates evaluation cycles.
export function getServiceUrl() {
  return import.meta.env.VITE_API_BASE_URL || '/xiaozhi';
}
