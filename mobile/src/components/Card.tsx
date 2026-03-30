import { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';

type CardProps = {
  title: string;
  subtitle?: string | null;
  children: ReactNode;
};

export function Card({ title, subtitle, children }: CardProps) {
  return (
    <View style={styles.card}>
      <Text style={styles.title}>{title}</Text>
      {subtitle ? <Text style={styles.subtitle}>{subtitle}</Text> : null}
      <View style={styles.body}>{children}</View>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    borderRadius: 18,
    padding: 16,
    backgroundColor: '#fffaf2',
    borderWidth: 1,
    borderColor: '#e7dcca',
    gap: 10,
  },
  title: {
    fontSize: 18,
    fontWeight: '800',
    color: '#2d2218',
  },
  subtitle: {
    fontSize: 13,
    lineHeight: 18,
    color: '#74614b',
  },
  body: {
    gap: 10,
  },
});
