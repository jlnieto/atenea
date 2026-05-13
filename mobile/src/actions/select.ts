import { Alert } from 'react-native';

type SelectActionOption<T extends string> = {
  id: T;
  label: string;
  style?: 'default' | 'destructive';
};

export function selectAction<T extends string>(
  title: string,
  message: string,
  options: SelectActionOption<T>[],
  cancelLabel = 'Cancelar'
) {
  return new Promise<T | null>((resolve) => {
    Alert.alert(title, message, [
      {
        text: cancelLabel,
        style: 'cancel',
        onPress: () => resolve(null),
      },
      ...options.map((option) => ({
        text: option.label,
        style: option.style,
        onPress: () => resolve(option.id),
      })),
    ]);
  });
}
