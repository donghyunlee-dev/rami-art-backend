set search_path = rami_art_studio, extensions;

alter table director_career
  drop constraint ck_director_career_title,
  drop constraint ck_director_career_period;

alter table director_career alter column title type varchar(251);

update director_career
set title = btrim(concat_ws(' ', nullif(btrim(period), ''), title));

alter table director_career
  drop column period,
  add constraint ck_director_career_title check(char_length(btrim(title)) between 1 and 251);
