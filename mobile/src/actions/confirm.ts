import { Alert } from 'react-native';

export function confirmAction(
  title: string,
  message: string,
  continueLabel = 'Continue'
) {
  return new Promise<boolean>((resolve) => {
    Alert.alert(title, message, [
      {
        text: 'Cancel',
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
