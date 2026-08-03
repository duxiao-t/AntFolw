import { getIntl } from '@umijs/max';
import { Button, Card, Result } from 'antd';
import React from 'react';
import { ErrorBoundary as ReactErrorBoundary, type FallbackProps } from 'react-error-boundary';

function isChunkLoadError(error: Error): boolean {
  return (
    error.name === 'ChunkLoadError' ||
    /(?:loading|failed to load) (?:css )?chunk/i.test(error.message) ||
    /Failed to fetch dynamically imported module/i.test(error.message)
  );
}

function getSubTitleId(isChunkError: boolean, isOffline: boolean): string {
  if (!isChunkError) return 'app.error.render.description';
  return isOffline
    ? 'app.error.chunk.description.offline'
    : 'app.error.chunk.description.online';
}

function renderErrorFallback(
  error: Error,
  isOnline: boolean,
  onRetry: () => void,
  onReload: () => void,
) {
  const intl = getIntl();
  const isOffline = !isOnline;
  const isChunkError = isChunkLoadError(error);

  return (
    <Card variant="borderless" style={{ margin: 24 }}>
      <Result
        status="error"
        title={intl.formatMessage({
          id: isChunkError ? 'app.error.chunk.title' : 'app.error.render.title',
          defaultMessage: isChunkError
            ? 'Failed to load page'
            : 'Something went wrong',
        })}
        subTitle={intl.formatMessage({
          id: getSubTitleId(isChunkError, isOffline),
          defaultMessage:
            isChunkError && isOffline
              ? 'Your network connection has been lost. Please check your connection and reload.'
              : isChunkError
                ? 'Page resources failed to load. Please reload and try again.'
                : 'Sorry, an error occurred on this page. Please reload or go back to the home page.',
        })}
        extra={[
          isChunkError && (
            <Button type="primary" key="retry" onClick={onRetry}>
              {intl.formatMessage({
                id: 'app.error.retry',
                defaultMessage: 'Retry',
              })}
            </Button>
          ),
          <Button
            type={isChunkError ? 'default' : 'primary'}
            key="reload"
            onClick={onReload}
          >
            {intl.formatMessage({
              id: 'app.error.reload',
              defaultMessage: 'Reload Page',
            })}
          </Button>,
          <Button href="/" key="home">
            {intl.formatMessage({
              id: 'app.error.home',
              defaultMessage: 'Back Home',
            })}
          </Button>,
        ].filter(Boolean)}
      />
    </Card>
  );
}

function ErrorFallback({
  error,
  onRetry,
}: FallbackProps & { onRetry(): void }) {
  const [isOnline, setIsOnline] = React.useState(
    typeof navigator !== 'undefined' ? navigator.onLine : true,
  );

  React.useEffect(() => {
    const handleOnline = () => {
      setIsOnline(true);
      if (isChunkLoadError(error as Error)) {
        window.location.reload();
      }
    };
    const handleOffline = () => {
      setIsOnline(false);
    };
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, [error]);

  return renderErrorFallback(error as Error, isOnline, onRetry, () => {
    window.location.reload();
  });
}

function ErrorBoundaryImpl({ children }: { children: React.ReactNode }) {
  const [retryCount, setRetryCount] = React.useState(0);

  return (
    <ReactErrorBoundary
      key={retryCount}
      fallbackRender={(fallbackProps) => (
        <ErrorFallback
          {...fallbackProps}
          onRetry={() => setRetryCount((count) => count + 1)}
        />
      )}
      onError={(error, info) => {
        console.error('[ErrorBoundary]', error, info.componentStack);
      }}
    >
      {children}
    </ReactErrorBoundary>
  );
}

/**
 * Offline-aware error boundary built on `react-error-boundary`.
 * The class shell satisfies ProLayout's `ComponentClass` ErrorBoundary prop.
 */
export default class ErrorBoundary extends React.Component<{
  children: React.ReactNode;
}> {
  override render() {
    return <ErrorBoundaryImpl>{this.props.children}</ErrorBoundaryImpl>;
  }
}