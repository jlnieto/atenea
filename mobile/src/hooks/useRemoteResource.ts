import { useCallback, useEffect, useRef, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';

type ResourceState<T> = {
  data: T | null;
  error: string | null;
  loading: boolean;
  refreshing: boolean;
  reload: (options?: { silent?: boolean }) => Promise<void>;
  setData: Dispatch<SetStateAction<T | null>>;
};

type UseRemoteResourceOptions = {
  refreshIntervalMs?: number;
};

export function useRemoteResource<T>(
  loader: () => Promise<T>,
  deps: unknown[],
  options?: UseRemoteResourceOptions
): ResourceState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const hasLoadedRef = useRef(false);

  const reload = useCallback(async (options?: { silent?: boolean }) => {
    const silent = options?.silent === true;

    if (hasLoadedRef.current && !silent) {
      setRefreshing(true);
    } else if (!hasLoadedRef.current) {
      setLoading(true);
    }
    setError(null);
    try {
      const result = await loader();
      setData(result);
      hasLoadedRef.current = true;
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : 'Unknown error');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, deps);

  useEffect(() => {
    hasLoadedRef.current = false;
    setData(null);
    setError(null);
    setLoading(true);
    setRefreshing(false);
  }, deps);

  useEffect(() => {
    void reload();
  }, [reload]);

  useEffect(() => {
    if (!options?.refreshIntervalMs) {
      return undefined;
    }

    const intervalId = setInterval(() => {
      void reload({ silent: true });
    }, options.refreshIntervalMs);

    return () => clearInterval(intervalId);
  }, [options?.refreshIntervalMs, reload]);

  return { data, error, loading, refreshing, reload, setData };
}
