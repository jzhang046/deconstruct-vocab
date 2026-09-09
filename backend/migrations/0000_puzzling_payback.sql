CREATE TABLE `usage_counters` (
	`user_id` text NOT NULL,
	`date` text NOT NULL,
	`translate_count` integer DEFAULT 0 NOT NULL,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE no action
);
--> statement-breakpoint
CREATE UNIQUE INDEX `usage_counters_user_date` ON `usage_counters` (`user_id`,`date`);--> statement-breakpoint
CREATE TABLE `users` (
	`id` text PRIMARY KEY NOT NULL,
	`email` text NOT NULL,
	`name` text NOT NULL,
	`avatar_url` text,
	`oauth_provider` text NOT NULL,
	`oauth_subject` text NOT NULL,
	`created_at` integer NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `users_email_unique` ON `users` (`email`);--> statement-breakpoint
CREATE TABLE `vocabulary` (
	`id` text PRIMARY KEY NOT NULL,
	`user_id` text NOT NULL,
	`word` text NOT NULL,
	`phonetic` text DEFAULT '' NOT NULL,
	`meaning` text NOT NULL,
	`frequency` integer DEFAULT 0 NOT NULL,
	`alt_script` text,
	`language_pair_id` text NOT NULL,
	`created_at` integer NOT NULL,
	`updated_at` integer NOT NULL,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE no action
);
--> statement-breakpoint
CREATE UNIQUE INDEX `vocabulary_user_word_pair` ON `vocabulary` (`user_id`,`word`,`language_pair_id`);