import { Pressable, StyleSheet, Text } from 'react-native';

export type ActionButtonTone = 'default' | 'secondary' | 'info' | 'warning' | 'danger';
export type ActionButtonProminence = 'default' | 'high';

type ActionButtonProps = {
  label: string;
  onPress?: () => void;
  disabled?: boolean;
  tone?: ActionButtonTone;
  prominence?: ActionButtonProminence;
};

export function ActionButton({
  label,
  onPress,
  disabled,
  tone = 'default',
  prominence = 'default',
}: ActionButtonProps) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={[
        styles.button,
        tone === 'secondary' && styles.buttonSecondary,
        tone === 'info' && styles.buttonInfo,
        tone === 'warning' && styles.buttonWarning,
        tone === 'danger' && styles.buttonDanger,
        prominence === 'high' && styles.buttonHigh,
        disabled && styles.buttonDisabled,
      ]}
    >
      <Text
        style={[
          styles.label,
          tone === 'secondary' && styles.labelSecondary,
          tone === 'info' && styles.labelInfo,
          tone === 'warning' && styles.labelWarning,
          tone === 'danger' && styles.labelDanger,
          prominence === 'high' && styles.labelHigh,
          disabled && styles.labelDisabled,
        ]}
      >
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderRadius: 12,
    backgroundColor: '#164b3f',
  },
  buttonSecondary: {
    backgroundColor: '#fffdf8',
    borderWidth: 1,
    borderColor: '#8b765a',
  },
  buttonInfo: {
    backgroundColor: '#10566b',
    borderWidth: 1,
    borderColor: '#10566b',
  },
  buttonWarning: {
    backgroundColor: '#855200',
    borderWidth: 1,
    borderColor: '#855200',
  },
  buttonDanger: {
    backgroundColor: '#8e2c24',
    borderWidth: 1,
    borderColor: '#8e2c24',
  },
  buttonHigh: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderRadius: 14,
  },
  buttonDisabled: {
    backgroundColor: '#d7d0c6',
    borderColor: '#d7d0c6',
  },
  label: {
    fontSize: 13,
    fontWeight: '800',
    color: '#ffffff',
  },
  labelSecondary: {
    color: '#2f2419',
  },
  labelInfo: {
    color: '#ffffff',
  },
  labelWarning: {
    color: '#ffffff',
  },
  labelDanger: {
    color: '#ffffff',
  },
  labelHigh: {
    fontSize: 14,
  },
  labelDisabled: {
    color: '#5f574c',
  },
});
