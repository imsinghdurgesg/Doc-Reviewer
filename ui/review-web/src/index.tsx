import { createRoot } from "react-dom/client";
import { App } from "@/app/App";

const container = document.getElementById("root");
if (!container) {
  // Fail loudly. A silent no-op here produces a blank page with no console output, which is the
  // hardest possible starting point for diagnosis.
  throw new Error("Root container #root is missing from index.html");
}

createRoot(container).render(<App />);
