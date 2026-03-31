import { StatusBar } from 'expo-status-bar';
import { StyleSheet, View } from 'react-native';
import { useEffect, useState } from 'react';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppShell, AppTabId } from './src/AppShell';
import { PendingActionCenterProvider } from './src/actions/PendingActionCenter';
import { AuthProvider, useAuth } from './src/auth/AuthContext';
import { NotificationCenterProvider, useNotificationCenter } from './src/notifications/NotificationCenter';
import { LoginScreen } from './src/screens/LoginScreen';

export default function App() {
  return (
    <SafeAreaProvider>
      <AuthProvider>
        <PendingActionCenterProvider>
          <NotificationCenterProvider>
            <RootApp />
          </NotificationCenterProvider>
        </PendingActionCenterProvider>
      </AuthProvider>
    </SafeAreaProvider>
  );
}

function RootApp() {
  const insets = useSafeAreaInsets();
  const [selectedProjectId, setSelectedProjectId] = useState<number | null>(null);
  const [selectedSessionId, setSelectedSessionId] = useState<number | null>(null);
  const [activeTab, setActiveTab] = useState<AppTabId>('core');
  const { session, logout } = useAuth();
  const { consumePendingRoute, clearNotifications } = useNotificationCenter();

  useEffect(() => {
    const route = consumePendingRoute();
    if (route == null) {
      return;
    }
    if (route.sessionId != null) {
      setSelectedSessionId(route.sessionId);
    }
    setActiveTab(route.tab);
  }, [consumePendingRoute, session]);

  useEffect(() => {
    if (session != null) {
      return;
    }
    setSelectedProjectId(null);
    setSelectedSessionId(null);
    setActiveTab('core');
    clearNotifications();
  }, [session, clearNotifications]);

  return (
    <View style={styles.root}>
      <StatusBar style="dark" backgroundColor="#ffffff" translucent={false} />
      <View style={[styles.systemTopBar, { height: insets.top }]} />
      <View style={styles.appViewport}>
        {session == null ? (
          <LoginScreen />
        ) : (
          <AppShell
            activeTab={activeTab}
            onChangeTab={setActiveTab}
            selectedProjectId={selectedProjectId}
            onSelectProject={setSelectedProjectId}
            selectedSessionId={selectedSessionId}
            onSelectSession={setSelectedSessionId}
            operatorKey={session.operator.email}
            operatorName={session.operator.displayName}
            onLogout={() => void logout()}
          />
        )}
      </View>
      <View style={[styles.systemBottomBar, { height: insets.bottom }]} />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#ffffff',
  },
  systemTopBar: {
    backgroundColor: '#ffffff',
  },
  appViewport: {
    flex: 1,
  },
  systemBottomBar: {
    backgroundColor: '#ffffff',
  },
});
