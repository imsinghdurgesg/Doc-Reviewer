// Production configuration. Architecture §7.2.
const { merge } = require("webpack-merge");
const MiniCssExtractPlugin = require("mini-css-extract-plugin");
const CssMinimizerPlugin = require("css-minimizer-webpack-plugin");
const TerserPlugin = require("terser-webpack-plugin");
const common = require("./webpack.common.js");

module.exports = merge(common, {
  mode: "production",
  // Hidden source maps: stack traces stay mappable for an operator with the map file, while the
  // bundle carries no sourceMappingURL pointing a browser at readable source (§7.2 "according to
  // the selected security policy").
  devtool: "hidden-source-map",
  module: {
    rules: [
      {
        test: /\.css$/,
        // Overrides the common style-loader rule: injected <style> tags block first paint and
        // cannot be cached separately from the JS bundle.
        use: [MiniCssExtractPlugin.loader, "css-loader"],
      },
    ],
  },
  optimization: {
    minimize: true,
    minimizer: [new TerserPlugin(), new CssMinimizerPlugin()],
    splitChunks: {
      chunks: "all",
      cacheGroups: {
        vendor: {
          test: /[\\/]node_modules[\\/]/,
          name: "vendor",
          priority: 10,
        },
      },
    },
  },
  plugins: [
    new MiniCssExtractPlugin({
      filename: "assets/[name].[contenthash].css",
    }),
  ],
  performance: {
    // Warn rather than error: a hard failure on bundle size would block a legitimate build.
    hints: "warning",
    maxEntrypointSize: 900000,
    maxAssetSize: 900000,
  },
});
