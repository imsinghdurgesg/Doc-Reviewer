// Development configuration. Architecture §7.2.
const { merge } = require("webpack-merge");
const common = require("./webpack.common.js");

module.exports = merge(common, {
  mode: "development",
  devtool: "eval-source-map",
  devServer: {
    port: 3000,
    // §7.4: the UI uses same-origin relative paths (/api), so the dev server proxies them. In
    // Compose, nginx does this instead — the application code is identical either way.
    proxy: [
      {
        context: ["/api"],
        // review-core on 8080. The spec text says 8081, which is document-processing — an internal
        // worker the browser must never reach (§3, §8.2). Proxying there would 404 every request
        // and, worse, would be a browser talking to a service that has no public API.
        target: process.env.API_TARGET || "http://localhost:8080",
        changeOrigin: true,
      },
    ],
    // React Router needs unknown paths served index.html, or a refresh on /comparisons/{id} 404s.
    historyApiFallback: true,
    hot: true,
  },
});
