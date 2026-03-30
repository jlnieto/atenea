import { StyleSheet, Text, View } from 'react-native';

type StatePillProps = {
  label: string;
  tone?: 'default' | 'good' | 'warning' | 'danger';
};

export function StatePill({ label, tone = 'default' }: StatePillProps) {
  return (
    <View style={[styles.pill, toneStyles[tone].pill]}>
      <Text style={[styles.text, toneStyles[tone].text]}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  pill: {
    alignSelf: 'flex-start',
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 999,
  },
  text: {
    fontSize: 12,
    fontWeight: '800',
  },
});

const toneStyles = {
  default: StyleSheet.create({
    pill: { backgroundColor: '#e5dccf' },
    text: { color: '#594632' },
  }),
  good: StyleSheet.create({
    pill: { backgroundColor: '#d5eadf' },
    text: { color: '#21573c' },
  }),
  warning: StyleSheet.create({
    pill: { backgroundColor: '#f5e0bb' },
    text: { color: '#7a4c06' },
  }),
  danger: StyleSheet.create({
    pill: { backgroundColor: '#f0d1cb' },
    text: { color: '#7b2517' },
  }),
};
