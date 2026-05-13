import { Pressable, StyleSheet, Text, View } from 'react-native';
import { AppIcon, AppIconName } from './AppIcon';

type IconActionLinkProps = {
  label: string;
  icon: AppIconName;
  onPress: () => void;
  color?: string;
  size?: number;
  compact?: boolean;
};

export function IconActionLink({
  label,
  icon,
  onPress,
  color = '#145f54',
  size = 16,
  compact = false,
}: IconActionLinkProps) {
  return (
    <Pressable onPress={onPress} style={[styles.linkButton, compact && styles.linkButtonCompact]}>
      <View style={[styles.iconWrap, compact && styles.iconWrapCompact]}>
        <AppIcon name={icon} size={size} color={color} />
      </View>
      <Text style={[styles.label, compact && styles.labelCompact, { color }]} numberOfLines={1}>
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  linkButton: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    flexShrink: 1,
  },
  linkButtonCompact: {
    gap: 4,
  },
  iconWrap: {
    width: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconWrapCompact: {
    width: 14,
  },
  label: {
    fontSize: 14,
    fontWeight: '800',
  },
  labelCompact: {
    fontSize: 12,
  },
});
