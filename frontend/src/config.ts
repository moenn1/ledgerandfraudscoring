type RuntimeConfig = {
  apiBaseUrl?: string;
  apiBearerToken?: string;
};

function runtimeConfig(): RuntimeConfig {
  if (typeof window === "undefined") {
    return {};
  }
  return window.__LEDGERFORGE_RUNTIME_CONFIG__ ?? {};
}

export const apiBaseUrl = runtimeConfig().apiBaseUrl || import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";
export const apiBearerToken = runtimeConfig().apiBearerToken || import.meta.env.VITE_API_BEARER_TOKEN;

