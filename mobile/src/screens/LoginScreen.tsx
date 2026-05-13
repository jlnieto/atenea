import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useAuth } from '../auth/AuthContext';
import { Card } from '../components/Card';

export function LoginScreen() {
  const { login, loading } = useAuth();
  const [operator, setOperator] = useState('operator@atenea.local');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setError(null);
    try {
      await login({
        email: operator.trim(),
        password,
      });
    } catch (loginError) {
      setError(loginError instanceof Error ? loginError.message : 'No se pudo iniciar sesión');
    }
  };

  return (
    <View style={styles.container}>
      <Card
        title="Acceso de operador"
        subtitle="Autentícate en Atenea para operar los flujos móviles protegidos."
      >
        <TextInput
          value={operator}
          onChangeText={setOperator}
          placeholder="operator@atenea.local"
          placeholderTextColor="#8b7c6b"
          style={styles.input}
          autoCapitalize="none"
          autoCorrect={false}
        />
        <TextInput
          value={password}
          onChangeText={setPassword}
          placeholder="Contraseña"
          placeholderTextColor="#8b7c6b"
          style={styles.input}
          secureTextEntry
        />
        <Pressable
          style={[styles.button, (loading || !operator.trim() || !password) && styles.buttonDisabled]}
          onPress={() => void submit()}
          disabled={loading || !operator.trim() || !password}
        >
          <Text style={styles.buttonLabel}>{loading ? 'Entrando...' : 'Entrar'}</Text>
        </Pressable>
        {error ? <Text style={styles.error}>{error}</Text> : null}
      </Card>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    padding: 20,
    backgroundColor: '#f3efe6',
  },
  input: {
    borderWidth: 1,
    borderColor: '#dccfb8',
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 10,
    backgroundColor: '#fffdf8',
    fontSize: 14,
    color: '#2d2218',
  },
  button: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderRadius: 12,
    backgroundColor: '#2e6a57',
  },
  buttonDisabled: {
    backgroundColor: '#cfc8be',
  },
  buttonLabel: {
    fontSize: 14,
    fontWeight: '800',
    color: '#f7f3ea',
    textAlign: 'center',
  },
  error: {
    fontSize: 13,
    fontWeight: '700',
    color: '#9f3024',
  },
});
