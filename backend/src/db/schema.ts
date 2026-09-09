import { sqliteTable, text, integer, uniqueIndex } from "drizzle-orm/sqlite-core";

export const users = sqliteTable("users", {
  id: text("id").primaryKey(),
  email: text("email").notNull().unique(),
  name: text("name").notNull(),
  avatarUrl: text("avatar_url"),
  oauthProvider: text("oauth_provider").notNull(),
  oauthSubject: text("oauth_subject").notNull(),
  createdAt: integer("created_at", { mode: "timestamp" }).notNull(),
});

export const vocabulary = sqliteTable(
  "vocabulary",
  {
    id: text("id").primaryKey(),
    userId: text("user_id")
      .notNull()
      .references(() => users.id),
    word: text("word").notNull(),
    phonetic: text("phonetic").notNull().default(""),
    meaning: text("meaning").notNull(),
    frequency: integer("frequency").notNull().default(0),
    altScript: text("alt_script"),
    languagePairId: text("language_pair_id").notNull(),
    createdAt: integer("created_at", { mode: "timestamp" }).notNull(),
    updatedAt: integer("updated_at", { mode: "timestamp" }).notNull(),
  },
  (table) => ({
    // Mirrors VocabularyStore's (word, languagePairId) identity rule, scoped per user.
    userWordPair: uniqueIndex("vocabulary_user_word_pair").on(
      table.userId,
      table.word,
      table.languagePairId,
    ),
  }),
);

export const usageCounters = sqliteTable(
  "usage_counters",
  {
    userId: text("user_id")
      .notNull()
      .references(() => users.id),
    date: text("date").notNull(), // YYYY-MM-DD (UTC)
    translateCount: integer("translate_count").notNull().default(0),
  },
  (table) => ({
    userDate: uniqueIndex("usage_counters_user_date").on(table.userId, table.date),
  }),
);
