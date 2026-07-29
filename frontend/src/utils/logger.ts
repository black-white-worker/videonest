type LogLevel = 'debug' | 'info' | 'warn' | 'error'

const configuredLevel = (
  import.meta.env.VITE_LOG_LEVEL || (import.meta.env.DEV ? 'debug' : 'warn')
) as LogLevel

const levelWeight: Record<LogLevel, number> = {
  debug: 10,
  info: 20,
  warn: 30,
  error: 40
}

function write(
  level: LogLevel,
  event: string,
  context?: Record<string, unknown>
) {
  if (levelWeight[level] < levelWeight[configuredLevel]) return

  const entry = {
    time: new Date().toISOString(),
    level,
    event,
    ...context
  }

  if (level === 'error') {
    console.error('[VideoNest]', entry)
  } else if (level === 'warn') {
    console.warn('[VideoNest]', entry)
  } else if (level === 'info') {
    console.info('[VideoNest]', entry)
  } else {
    console.debug('[VideoNest]', entry)
  }
}

export const logger = {
  debug: (event: string, context?: Record<string, unknown>) =>
    write('debug', event, context),
  info: (event: string, context?: Record<string, unknown>) =>
    write('info', event, context),
  warn: (event: string, context?: Record<string, unknown>) =>
    write('warn', event, context),
  error: (event: string, context?: Record<string, unknown>) =>
    write('error', event, context)
}
