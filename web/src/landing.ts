// The introduction page at /. The app moved from / to /app/, so anything
// still meant for the app is sent on there: links to its pages (/#/records),
// the notices older servers redirect to (/?email=confirmed), and the app
// installed from / before the move, which still opens at /.
import './styles.css';
import './landing.css';

const forApp =
  location.hash.startsWith('#/') ||
  /[?&](email|signin_error)=/.test(location.search) ||
  matchMedia('(display-mode: standalone)').matches;
if (forApp) location.replace('/app/' + location.search + location.hash);
