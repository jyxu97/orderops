import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <section className="panel">
      <h1>Page not found</h1>
      <p>
        Nothing here. <Link to="/orders">Back to orders</Link>.
      </p>
    </section>
  );
}
