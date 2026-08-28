import { createBrowserRouter, Navigate } from "react-router-dom";
import { AppLayout } from "@/app/AppLayout";
import { ComparisonWorkspace } from "@/features/comparisons/ComparisonWorkspace";
import { HistoryPage } from "@/features/history/HistoryPage";
import { AdminPage } from "@/features/admin/AdminPage";
import { UploadPage } from "@/features/uploads/UploadPage";

/**
 * Routes. Architecture §7.3.
 *
 * A comparison is addressable by ID so a reviewer can share a link to a specific review — which is
 * the whole point of preserving completed comparisons (§19.2).
 *
 * `/` is the upload page rather than a dashboard: the product's primary action is reviewing a
 * document, and a landing page that requires one more click to reach it serves nobody.
 */
export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppLayout />,
    children: [
      { index: true, element: <UploadPage /> },
      { path: "history", element: <HistoryPage /> },
      { path: "standards", element: <AdminPage /> },
      { path: "comparisons/:comparisonId", element: <ComparisonWorkspace /> },
      // Unknown paths go to upload, not to a 404 screen: every wrong URL here is a mistyped or
      // stale comparison link, and the useful response is the place work starts.
      { path: "*", element: <Navigate to="/" replace /> },
    ],
  },
]);
