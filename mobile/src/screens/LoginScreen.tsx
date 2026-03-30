import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useAuth } from '../auth/AuthContext';
import { Card } from '../components/Card';

export function LoginScreen() {
  const { login, loading } = useAuth();
  const [email, setEmail] = useState('operator@atenea.local');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setError(null);
    try {
      await login({
        email: email.trim(),
        password,
      });
    } catch (loginError) {
      setError(loginError instanceof Error ? loginError.message : 'Login failed');
    }
  };

  return (
    <View style={styles.container}>
      <Card
        title="Operator Login"
        subtitle="Authenticate against Atenea mobile auth to operate protected mobile flows."
      >
        <TextInput
          value={email}
          onChangeText={setEmail}
          placeholder="operator@atenea.local"
          placeholderTextColor="#8b7c6b"
          style={styles.input}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="email-address"
        />
        <TextInput
          value={password}
          onChangeText={setPassword}
          placeholder="Password"
          placeholderTextColor="#8b7c6b"
          style={styles.input}
          secureTextEntry
        />
        <Pressable
          style={[styles.button, (loading || !email.trim() || !password) && styles.buttonDisabled]}
          onPress={() => void submit()}
          disabled={loading || !email.trim() || !password}
        >
          <Text style={styles.buttonLabel}>{loading ? 'Signing in...' : 'Sign in'}</Text>
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
