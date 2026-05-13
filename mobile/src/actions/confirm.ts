import { Alert } from 'react-native';

export function confirmAction(
  title: string,
  message: string,
  continueLabel = 'Continuar'
) {
  return new Promise<boolean>((resolve) => {
    Alert.alert(title, message, [
      {
        text: 'Cancelar',
        style: 'cancel',
        onPress: () => resolve(false),
      },
      {
        text: continueLabel,
        style: 'destructive',
        onPress: () => resolve(true),
      },
    ]);
  });
}
