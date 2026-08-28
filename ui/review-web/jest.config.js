/**
 * Jest configuration. Architecture §7.1, §36.
 *
 * Plain JS, not TypeScript. A jest.config.ts requires `ts-node` purely so Jest can read its own
 * config — a dependency that buys nothing here and was previously satisfied only by a hoisted
 * transitive copy, so `npm ci` in a clean container failed while local runs passed.
 *
 * @type {import('jest').Config}
 */
module.exports = {
  preset: "ts-jest",
  testEnvironment: "jsdom",
  setupFilesAfterEnv: ["<rootDir>/src/test/setup.ts"],
  moduleNameMapper: {
    "^@/(.*)$": "<rootDir>/src/$1",
  },
  testMatch: ["<rootDir>/src/**/*.test.ts", "<rootDir>/src/**/*.test.tsx"],
  collectCoverageFrom: ["src/**/*.{ts,tsx}", "!src/test/**", "!src/index.tsx"],
};
