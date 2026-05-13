import Svg, { Circle, Line, Path, Rect } from 'react-native-svg';

export type AppIconName =
  | 'arrow-left'
  | 'arrow-right'
  | 'check'
  | 'close'
  | 'conversation'
  | 'logout'
  | 'menu'
  | 'microphone'
  | 'refresh'
  | 'send-up'
  | 'spark'
  | 'warning';

type AppIconProps = {
  name: AppIconName;
  size?: number;
  color?: string;
  strokeWidth?: number;
};

export function AppIcon({
  name,
  size = 20,
  color = '#dfeae5',
  strokeWidth = 1.9,
}: AppIconProps) {
  const common = {
    fill: 'none' as const,
    stroke: color,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    strokeWidth,
  };

  return (
    <Svg width={size} height={size} viewBox="0 0 24 24">
      {name === 'microphone' ? (
        <>
          <Rect x="8" y="3.5" width="8" height="12" rx="4" {...common} />
          <Path d="M5.5 11.5a6.5 6.5 0 0 0 13 0" {...common} />
          <Line x1="12" y1="18" x2="12" y2="21" {...common} />
          <Line x1="8.5" y1="21" x2="15.5" y2="21" {...common} />
        </>
      ) : null}
      {name === 'send-up' ? (
        <>
          <Line x1="12" y1="18.5" x2="12" y2="6" {...common} />
          <Path d="M7.5 10.5 12 6l4.5 4.5" {...common} />
        </>
      ) : null}
      {name === 'close' ? (
        <>
          <Line x1="6.5" y1="6.5" x2="17.5" y2="17.5" {...common} />
          <Line x1="17.5" y1="6.5" x2="6.5" y2="17.5" {...common} />
        </>
      ) : null}
      {name === 'conversation' ? (
        <>
          <Path d="M5 7.5A2.5 2.5 0 0 1 7.5 5h9A2.5 2.5 0 0 1 19 7.5v6A2.5 2.5 0 0 1 16.5 16H10l-4 3v-3H7.5A2.5 2.5 0 0 1 5 13.5v-6Z" {...common} />
          <Line x1="8.5" y1="9.5" x2="15.5" y2="9.5" {...common} />
          <Line x1="8.5" y1="12.5" x2="13.5" y2="12.5" {...common} />
        </>
      ) : null}
      {name === 'logout' ? (
        <>
          <Path d="M10 5.5H7.5A2.5 2.5 0 0 0 5 8v8a2.5 2.5 0 0 0 2.5 2.5H10" {...common} />
          <Line x1="11" y1="12" x2="19" y2="12" {...common} />
          <Path d="M15.5 8.5 19 12l-3.5 3.5" {...common} />
        </>
      ) : null}
      {name === 'menu' ? (
        <>
          <Line x1="5" y1="7" x2="19" y2="7" {...common} />
          <Line x1="5" y1="12" x2="19" y2="12" {...common} />
          <Line x1="5" y1="17" x2="19" y2="17" {...common} />
        </>
      ) : null}
      {name === 'arrow-left' ? (
        <>
          <Line x1="18" y1="12" x2="6" y2="12" {...common} />
          <Path d="M10.5 7.5 6 12l4.5 4.5" {...common} />
        </>
      ) : null}
      {name === 'arrow-right' ? (
        <>
          <Line x1="6" y1="12" x2="18" y2="12" {...common} />
          <Path d="M13.5 7.5 18 12l-4.5 4.5" {...common} />
        </>
      ) : null}
      {name === 'refresh' ? (
        <>
          <Path d="M18 8.5A7 7 0 0 0 6.7 6.6" {...common} />
          <Path d="M6 8V5h3" {...common} />
          <Path d="M6 15.5A7 7 0 0 0 17.3 17.4" {...common} />
          <Path d="M18 16v3h-3" {...common} />
        </>
      ) : null}
      {name === 'check' ? (
        <Path d="m5.5 12.5 4.2 4.2L18.5 8" {...common} />
      ) : null}
      {name === 'warning' ? (
        <>
          <Path d="M12 4.5 20 19H4l8-14.5Z" {...common} />
          <Line x1="12" y1="9" x2="12" y2="13.5" {...common} />
          <Circle cx="12" cy="16.7" r="0.7" fill={color} />
        </>
      ) : null}
      {name === 'spark' ? (
        <>
          <Path d="m12 4 1.8 4.2L18 10l-4.2 1.8L12 16l-1.8-4.2L6 10l4.2-1.8L12 4Z" {...common} />
          <Path d="m18.5 4.5.7 1.7 1.8.8-1.8.7-.7 1.8-.8-1.8-1.7-.7 1.7-.8.8-1.7Z" {...common} />
        </>
      ) : null}
    </Svg>
  );
}
