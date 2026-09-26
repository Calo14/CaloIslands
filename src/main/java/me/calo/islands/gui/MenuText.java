package me.calo.islands.gui;

/** Shared state colours and Spanish explanations for existing domain-service failures. */
public final class MenuText {
    private MenuText() { }
    public static String state(boolean active) { return active ? "§aactiva" : "§cinactiva"; }
    public static String error(String reason) {
        if (reason == null) return "No se pudo completar la acción.";
        return switch (reason) {
            case "Deactivate the region before resizing" -> "Desactiva la región antes de redimensionarla.";
            case "Deactivate the region before deleting" -> "Desactiva la región antes de eliminarla.";
            case "Delete associated cities first" -> "Elimina primero las ciudades de esta región.";
            case "City changed concurrently", "Region changed concurrently" -> "Los datos cambiaron. Consulta los detalles y confirma de nuevo.";
            default -> {
                if (reason.startsWith("Region overlaps ")) yield "La selección se superpone a otra región: " + reason.substring(16);
                if (reason.startsWith("City location is outside region ")) yield "La ciudad debe estar dentro de la región indicada.";
                if (reason.startsWith("City ") && reason.endsWith("would be outside the region")) yield "La nueva selección dejaría una ciudad fuera de su región.";
                if (reason.startsWith("World is not loaded: ")) yield "El mundo de la región no está cargado.";
                yield reason;
            }
        };
    }
}
