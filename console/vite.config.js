import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    // Port 3000 is often taken by another local dev server, so Vite will fall
    // back to 3001, 3002, etc. The gateway's admin CORS allowlist
    // (prism.admin.cors.allowed-origins) permits 3000 and 3001 by default.
    port: 3000,
  },
});
