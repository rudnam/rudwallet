// Activity: the Records page with what's waiting for a check on top, so
// review isn't a separate place to remember. #/records and #/review both
// open it (widgets and old links use either).
import type { Route } from '../../router';
import { t } from '../../i18n';
import { ReviewPage } from '../review/ReviewPage';
import { RecordsPage } from './RecordsPage';

export function ActivityPage({ route }: { route: Route }) {
  return <RecordsPage route={route} title={t('Activity')} above={<ReviewPage compact />} hideReview />;
}
