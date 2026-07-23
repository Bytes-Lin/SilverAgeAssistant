package com.example.silverageassistant.ui.navigation

enum class AppDestination(val route: String) {
    RoleSelection("role_selection"),
    ElderSetup("elder_setup"),
    FamilySetup("family_setup"),
    ElderHome("elder_home"),
    FamilyHome("family_home"),
    FamilyNotification("family_notification"),
    FamilyReminder("family_reminder"),
    FamilyModelConfiguration("family_model_configuration"),
    FamilyModelUsage("family_model_usage"),
    FamilyTodayStatus("family_today_status"),
    FamilySafetyMonitoringConfiguration("family_safety_monitoring_configuration"),
    FamilyEmergencyEvents("family_emergency_events"),
    Conversation("conversation"),
    Reminders("reminders"),
    LifeAssistant("life_assistant"),
    FamilyContacts("family_contacts"),
    Music("music"),
    Sos("sos"),
    Settings("settings"),
}
