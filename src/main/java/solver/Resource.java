package solver;

import java.util.HashMap;

public class Resource {
    private String id;
    private String label;
    private int capacity;
    private Placement placement;
    private HashMap<QueryType, Double> costs = new HashMap<>();

    public Resource() { }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public Placement getPlacement() {
        return placement;
    }

    public void setPlacement(Placement placement) {
        this.placement = placement;
    }

    public HashMap<QueryType, Double> getCosts() {
        return costs;
    }

    public Double getCost(QueryType type) {
        return costs.get(type);
    }

    public void setCosts(HashMap<QueryType, Double> costs) {
        this.costs = costs;
    }

    @Override
    public String toString() {
        return String.format("Resource#%d (%s)", id, label);
    }
}