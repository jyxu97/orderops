import { ApiError } from '../api/client';

interface Props {
  error: unknown;
  // Explicitly `| undefined` rather than only optional: exactOptionalPropertyTypes is on, so a
  // caller passing a conditionally-undefined handler would otherwise be a type error.
  onRetry?: (() => void) | undefined;
}

/**
 * Renders whatever the failure actually was.
 *
 * `ApiError` carries the backend's message, which is the useful part: "Insufficient inventory
 * for itemId=widget-a" tells the user what to do, "Request failed" does not.
 */
export function ErrorBanner({ error, onRetry }: Props) {
  if (!error) {
    return null;
  }

  const isApiError = error instanceof ApiError;
  const message = error instanceof Error ? error.message : 'Something went wrong.';
  const fieldErrors = isApiError ? Object.entries(error.fieldErrors) : [];

  return (
    <div className="banner banner--error" role="alert">
      <div className="banner__body">
        <strong>{isApiError ? `${error.status} ${error.body?.error ?? 'Error'}` : 'Error'}</strong>
        <span>{message}</span>
        {fieldErrors.length > 0 && (
          <ul className="banner__fields">
            {fieldErrors.map(([field, detail]) => (
              <li key={field}>
                <code>{field}</code>: {detail}
              </li>
            ))}
          </ul>
        )}
      </div>
      {onRetry && (
        <button type="button" className="button button--ghost" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}
