# Implementation steps

The project was built in two rounds: first the local application, then the AWS deployment.

## Round 1: local implementation (plus GitHub and Actions)

Covers completing the local implementation only, plus GitHub and GitHub Actions.

| What | Where |
|---|---|
| Instructions | `PLAN_with_two_phases.md` |
| Chat exports | `notes\implmentations\initial_run` |
| Related notes, resume bullets, interview questions, project errors, links | Everything in `./notes` except `notes\implmentations\aws_manually` and `notes\implmentations\remaining_steps_after_initial` |

## Round 2: deploy to AWS

This round starts after the app was completed locally, with all features and tests confirmed to run and pass.

The instructions below, and any nested or sibling links, folders and files, describe the steps needed to deploy the application to AWS with most features. The features present locally but missing in AWS are listed in `notes\implmentations\aws_manually\features_present_in_local_but_not_in_aws.txt`.

### Instructions, in order

1. Steps 1 to 9: `notes\implmentations\remaining_steps_after_initial\implementing_1_9_remaining_steps.txt`
2. Steps 10 to 12: `notes\implmentations\remaining_steps_after_initial\implementing_10_11_12_remaining_steps.txt`
3. Step 13 (the remainder): after steps 1 to 12 are implemented, the plan is detailed in `notes\implmentations\aws_manually\step_0_master_step_list.txt`

### Recorded demo on AWS

The Kafka-outage demo (video, step times and the checks run at each step) is in the root `README.md` under "Recorded Demo on AWS". What is recorded and what is still to record is tracked in `notes\implmentations\aws_manually\step_6_2_4_demo\1_recordings_for_demo_done_and_todo.txt`; the steps followed are in `notes\implmentations\aws_manually\step_6_2_4_demo\resume_point_2\kafka_outage\chosen_option_b_stop_and_start_kafka_from_console.txt`.
