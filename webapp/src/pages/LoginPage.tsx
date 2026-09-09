import { loginUrl } from "../api/client";

export function LoginPage() {
  return (
    <div className="centered-page">
      <div className="login-hero">
        <p className="wordmark">Deconstruct</p>
        <p className="tagline">Translate a sentence. Keep the words worth keeping.</p>
        <div className="frame login-frame">
          <ul className="login-features">
            <li>
              <strong>Translate</strong>
              <span>Type or speak a sentence and get its meaning, phonetic reading, and grammar notes.</span>
            </li>
            <li>
              <strong>Deconstruct</strong>
              <span>Every sentence breaks down into the words it's built from, one by one.</span>
            </li>
            <li>
              <strong>Remember</strong>
              <span>Save the words you want, and see how often you've run into them.</span>
            </li>
          </ul>
          <a className="button primary" href={loginUrl("google")}>
            Sign in with Google
          </a>
        </div>
      </div>
    </div>
  );
}
