import type { ReactNode } from 'react';
import { toSafeHref } from '@/lib/text/markdown';

interface MarkdownBodyProps {
  text: string;
  contentColor: string;
  className?: string;
}

function renderInline(text: string, contentColor: string, keyPrefix: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  const pattern = /(\*\*(.+?)\*\*|_(.+?)_|\[(.+?)\]\(([^)]+)\))/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  let index = 0;

  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) {
      nodes.push(text.slice(lastIndex, match.index));
    }

    if (match[2]) {
      nodes.push(
        <strong key={`${keyPrefix}-b-${index}`} style={{ color: contentColor }}>
          {match[2]}
        </strong>,
      );
    } else if (match[3]) {
      nodes.push(
        <em key={`${keyPrefix}-i-${index}`} style={{ color: contentColor }}>
          {match[3]}
        </em>,
      );
    } else if (match[4] && match[5]) {
      nodes.push(
        <a
          key={`${keyPrefix}-l-${index}`}
          href={toSafeHref(match[5])}
          target="_blank"
          rel="noopener noreferrer"
          className="underline"
          style={{ color: contentColor }}
        >
          {match[4]}
        </a>,
      );
    }

    index += 1;
    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < text.length) {
    nodes.push(text.slice(lastIndex));
  }

  return nodes.length > 0 ? nodes : [text];
}

/** Lightweight markdown preview matching Android RichTextParser styling. */
export function MarkdownBody({ text, contentColor, className = '' }: MarkdownBodyProps) {
  if (!text.trim()) return null;

  return (
    <div
      className={`whitespace-pre-wrap break-words text-[16px] leading-[1.55] tracking-[0.01em] ${className}`}
      style={{ color: contentColor }}
    >
      {text.split('\n').map((line, lineIndex) => {
        const bulletMatch = line.match(/^• (.*)$/);

        if (bulletMatch) {
          return (
            <div key={lineIndex} className="flex gap-2">
              <span>•</span>
              <span>{renderInline(bulletMatch[1], contentColor, `line-${lineIndex}`)}</span>
            </div>
          );
        }

        return (
          <div key={lineIndex}>
            {line ? renderInline(line, contentColor, `line-${lineIndex}`) : '\u00A0'}
          </div>
        );
      })}
    </div>
  );
}
