# Nearby Police Incidents

TheBrief now includes a battery-conscious public police-incident feature based on the official Norwegian Police **Politiloggen API**, presented as **Max**'s report - the 7th analyst character (a police shield badge, placed after Anja in the Assistants list).

## Default behavior
- Municipality: Ringerike (editable in Settings)
- All public Politiloggen categories enabled by default
- English category names, full translated text, and exact date + time throughout
- Norwegian incident text is translated online to English; the original Norwegian is retained where relevant
- Reports live under Max in the Assistants tab (not on the main dashboard) - his screen is titled "<Municipality> Police Report" and shows up to 20 incidents, newest first
- A background WorkManager check runs about every 2 hours when network is available and battery isn't low (same battery-friendly pattern as the custom weather alert check). Android may defer it under Doze/battery restrictions.
- New incidents trigger a heads-up push notification (own "Nearby Police Incidents" channel, same high-priority treatment as custom weather alerts), and the dashboard's top status bar shows a small 🛡️ icon next to the online/weather-alert icons whenever there's a new report - tapping it opens Max's full report and clears the icon.
- The first background run establishes a baseline so installing/updating the app does not generate a flood of notifications for old incidents.

## Categories
Events, Fire, Animals, Burglary, Rescue, Public order, Missing person, Maritime incident, Vandalism / property damage, Traffic, Theft, Accident, Violence, Weather, Other incidents.

## Important limitation
Politiloggen is a **public operational log**, not a live feed of every 112 call. Police may omit, delay, generalize, or update incidents for operational, privacy, or security reasons.

## Official source and attribution
The data is from Politiet / Politiloggen and is licensed under the Norwegian Licence for Open Government Data (NLOD) 2.0. The app includes visible attribution and a link to Politiloggen as required by the official usage guidance.

Official API: https://api.politiloggen.politiet.no/
Official public log: https://www.politiet.no/politiloggen
