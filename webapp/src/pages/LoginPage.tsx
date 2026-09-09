import { loginUrl } from "../api/client";

export function LoginPage() {
  return (
    <div className="centered-page">
      <div className="card login-card">
        <h1>Deconstruct</h1>
        <p>Learn languages by translating, then saving the words you want to remember.</p>
        <a className="button primary" href={loginUrl("google")}>
          Sign in with Google
        </a>
      </div>
    </div>
  );
}
