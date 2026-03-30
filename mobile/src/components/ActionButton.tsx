import { Pressable, StyleSheet, Text } from 'react-native';

type ActionButtonProps = {
  label: string;
  onPress?: () => void;
  disabled?: boolean;
};

export function ActionButton({ label, onPress, disabled }: ActionButtonProps) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={[styles.button, disabled && styles.buttonDisabled]}
    >
      <Text style={[styles.label, disabled && styles.labelDisabled]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderRadius: 12,
    backgroundColor: '#2e6a57',
  },
  buttonDisabled: {
    backgroundColor: '#d7d0c6',
  },
  label: {
    fontSize: 13,
    fontWeight: '800',
    color: '#f6f1e8',
  },
  labelDisabled: {
    color: '#7f7569',
  },
});
