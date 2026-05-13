const appJson = require('./app.json');

module.exports = ({ config }) => {
  const baseConfig = Object.keys(config ?? {}).length > 0 ? config : appJson.expo;

  return {
    ...baseConfig,
    name: process.env.EXPO_PUBLIC_ATENEA_APP_NAME || baseConfig.name,
  };
};
