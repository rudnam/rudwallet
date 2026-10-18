// The tags on a record: chips to remove, and a box to type new ones in
// (Enter or a comma adds one), suggesting the user's tags. The label is the
// caller's.
import { useId } from 'react';
import { t } from '../i18n';
import { Icon, icons } from './Icon';

/** The most tags one record has, and the longest tag (maxTags, maxTag in internal/wallet). */
export const maxTags = 10;
export const maxTag = 30;

/** Tidies a tag as typed: no "#" in front, single spaces. */
export function cleanTag(s: string): string {
  return s.trim().replace(/^[#＃]+/, '').trim().replace(/\s+/g, ' ');
}

/** tags with text added, unless it is empty or there already, ignoring case. */
export function addTag(tags: string[], text: string): string[] {
  const tag = cleanTag(text);
  if (!tag || tags.some((x) => x.toLowerCase() === tag.toLowerCase())) return tags;
  return [...tags, tag];
}

/** The change came from picking a suggestion, not from typing. */
function picked(e: Event): boolean {
  return !(e instanceof InputEvent) || e.inputType === 'insertReplacementText';
}

interface Props {
  /** The text box's id, for the label beside it. */
  id: string;
  tags: string[];
  onTags: (tags: string[]) => void;
  /** What is typed but not yet a tag; saving the form adds it. */
  text: string;
  onText: (text: string) => void;
  /** The user's tags, to suggest. */
  suggestions: string[];
}

export function TagField({ id, tags, onTags, text, onText, suggestions }: Props) {
  const list = useId();
  const full = tags.length >= maxTags;

  function add() {
    onTags(addTag(tags, text));
    onText('');
  }

  const unused = suggestions.filter((s) => !tags.some((x) => x.toLowerCase() === s.toLowerCase()));
  return (
    <div className="tag-field">
      {tags.length > 0 && (
        <ul className="chips" aria-label={t('Tags')}>
          {tags.map((tag) => (
            <li key={tag} className="chip tag">
              #{tag}
              <button
                type="button"
                className="tag-remove"
                aria-label={t('Remove tag {tag}', { tag })}
                onClick={() => onTags(tags.filter((x) => x !== tag))}
              >
                <Icon d={icons.close} className="inline-icon" />
              </button>
            </li>
          ))}
        </ul>
      )}
      <input
        id={id}
        value={text}
        maxLength={maxTag + 1}
        list={list}
        autoComplete="off"
        disabled={full}
        placeholder={full ? t('At most {n} tags', { n: maxTags }) : tags.length ? t('Add another') : t('Trip, work…')}
        onChange={(e) => {
          const v = e.target.value;
          // A comma ends a tag, as Enter does; picking a suggestion adds it.
          if (v.includes(',')) {
            let next = tags;
            for (const part of v.split(',')) next = addTag(next, part);
            onTags(next.slice(0, maxTags));
            onText('');
          } else if (unused.includes(v) && picked(e.nativeEvent)) {
            onTags(addTag(tags, v));
            onText('');
          } else onText(v);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && cleanTag(text)) {
            e.preventDefault();
            add();
          } else if (e.key === 'Backspace' && text === '' && tags.length > 0) {
            onTags(tags.slice(0, -1));
          }
        }}
        onBlur={() => cleanTag(text) && add()}
      />
      <datalist id={list}>
        {unused.map((s) => (
          <option key={s} value={s} />
        ))}
      </datalist>
    </div>
  );
}
