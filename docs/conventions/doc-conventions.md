# Documentation conventions

## Line width

Prose in `README.md` is wrapped at 80 characters: no line is longer than 80
characters, counting the indent of a list item. Wrap at word boundaries, so a
paragraph or list item reads as several short lines in the source and as one
block when rendered.

- A list item's continuation lines are indented to the text after its marker
  (two spaces for `- `, three for `1. `), so they stay part of the item.
- A paragraph that continues a list item, such as one after a code block, is
  indented the same way.
- An inline code span (`` `use attributes { … }` ``) and the punctuation
  attached to it stay on one line, even when that makes a line longer than 80
  characters.
- A link is wrapped between words of its text, never inside its URL.

### What is not wrapped

These are left as they are, because breaking them changes what they mean or
how they render:

- Headings.
- Tables.
- Fenced code blocks.
- A single token that is longer than the width, such as a URL.

### Keeping it wrapped

When a sentence is added to or removed from a paragraph or list item, re-wrap
that paragraph or item, so the lines stay full instead of leaving a short
line behind. Keep a list item's lines together: do not insert a blank line in
the middle of one.
