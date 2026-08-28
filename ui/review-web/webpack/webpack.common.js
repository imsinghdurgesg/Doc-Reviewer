// Shared webpack configuration. Architecture §7.2.
const path = require("path");
const HtmlWebpackPlugin = require("html-webpack-plugin");

module.exports = {
  // Explicit modern target, matching tsconfig's ES2022.
  //
  // Without this webpack falls back to `browserslist: defaults`, which reaches back far enough to
  // downlevel modern class syntax — needless transformation of output the app already compiles to
  // ES2022 itself.
  target: ["web", "es2022"],
  entry: path.resolve(__dirname, "../src/index.tsx"),
  output: {
    path: path.resolve(__dirname, "../dist"),
    filename: "assets/[name].[contenthash].js",
    chunkFilename: "assets/[name].[contenthash].chunk.js",
    publicPath: "/",
    clean: true,
  },
  resolve: {
    extensions: [".ts", ".tsx", ".js", ".jsx"],
    alias: {
      "@": path.resolve(__dirname, "../src"),
    },
  },
  module: {
    rules: [
      {
        test: /\.tsx?$/,
        exclude: /node_modules/,
        use: {
          loader: "ts-loader",
          options: {
            // transpileOnly: false — type errors FAIL the build (§7.2). Skipping type checking
            // here means a type error only surfaces in `npm run typecheck`, which is exactly the
            // step most likely to be skipped under time pressure.
            transpileOnly: false,
            // tsconfig.json sets noEmit for `tsc --noEmit` (§7.1). ts-loader honours it and then
            // emits nothing, which webpack reports as the opaque "TypeScript emitted no output".
            // Overridden here rather than removed from tsconfig, because the typecheck script needs
            // it and the loader is the only consumer that must not have it.
            compilerOptions: {
              noEmit: false,
            },
          },
        },
      },
      {
        test: /\.css$/,
        use: ["style-loader", "css-loader"],
      },
      {
        test: /\.(png|jpg|jpeg|gif|svg|woff2?)$/,
        type: "asset",
      },
    ],
  },
  plugins: [
    new HtmlWebpackPlugin({
      template: path.resolve(__dirname, "../public/index.html"),
    }),
  ],
};
