import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';

export function LoadingBlock({ label }: { label: string }) {
  return (
    <View style={styles.container}>
      <ActivityIndicator color="#2e6a57" />
      <Text style={styles.text}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 24,
    gap: 10,
  },
  text: {
    fontSize: 14,
    color: '#705b42',
  },
});
