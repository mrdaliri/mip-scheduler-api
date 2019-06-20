import ilog.concert.IloException;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Model class that converts a directed graph representing a
 * precedence constrained knapsack problem into a mathematical programming
 * model managed by CPLEX.
 *
 * @author Paul Bouman
 * @author Mohammad-Reza Daliri
 */

public class Model {
    private Input instance;

    private IloCplex cplex;

    private Map<Node, Map<Resource, IloNumVar>> varMap;

    /**
     * Constructor that takes a directed graph with the items and precedence constraints
     *
     * @param instance a directed graph with items
     * @throws IloException if something goes wrong with CPLEX
     */

    public Model(Input instance) throws IloException {
        // Initialize the instance variables
        this.instance = instance;
        this.cplex = new IloCplex();

        // Create a map to link items to variables
        this.varMap = new HashMap<>();

        // Initialize the model. It is important to initialize the variables first!
        addVariables();
        addCapacityConstraint();
        addAllocationConstraint();
        addTypePlacementConstraint();
        addObjective();

        // Optionally: export the model to a file, so we can check the mathematical
        // program generated by CPLEX
        cplex.exportModel("model.lp");
        // Optionally: suppress the output of CPLEX
        cplex.setOut(null);
    }


    /**
     * Solve the Mathematical Programming Model
     *
     * @throws IloException if something is wrong with CPLEX
     */
    public void solve() throws IloException {
        cplex.solve();
    }

    /**
     * Checks whether the current solution to the model is feasible
     *
     * @return the feasibility of the model
     * @throws IloException if something is wrong with CPLEX
     */
    public boolean isFeasible() throws IloException {
        return cplex.isPrimalFeasible();
    }

    /**
     * Create a list of the items for which the decision variables
     * are one in the current solution of the mathematical program.
     *
     * @return a list of selected items
     * @throws IloException if something is wrong with CPLEX
     */
    public Map<Node, Resource> getSolution() throws IloException {
        Map<Node, Resource> result = new HashMap<>();
        for (Node node : instance.getNodesGraph().getNodes()) {
            for (Resource resource : instance.getResourcesGraph().getNodes()) {
                IloNumVar var = varMap.get(node).get(resource);
                double value = cplex.getValue(var);
                if (value >= 0.5) {
                    result.put(node, resource);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Cleans up the CPLEX model in order to free up some memory.
     * This is important if you create many models, as memory used
     * by CPLEX is not freed up automatically by the JVM.
     *
     * @throws IloException if something goes wrong with CPLEX
     */
    public void cleanup() throws IloException {
        cplex.clearModel();
        cplex.end();
    }

    private void addObjective() throws IloException {
        IloNumExpr executionCost = cplex.constant(0);
        for (Node i : instance.getNodesGraph().getNodes()) {
            for (Resource j : instance.getResourcesGraph().getNodes()) {
                IloNumVar var = varMap.get(i).get(j);
                IloNumExpr term = cplex.prod(var, j.getCost(i.getQueryType()));
                executionCost = cplex.sum(executionCost, term);
            }
        }

        /*
        sum(i in nodes, j in resources) x[i][j]*executionCost[j][nodesData[i].qtype];
         */

        double maxLatency = 0;
        for (DirectedGraphArc<Resource, LinkProperty> link : instance.getResourcesGraph().getArcs()) {
            double linkLatency = link.getData().getLatency();
            if (linkLatency > maxLatency) {
                maxLatency = linkLatency;
            }
        }

        IloNumExpr communicationCost = cplex.constant(0);
        for (DirectedGraphArc<Node, EdgeProperty> edge : instance.getNodesGraph().getArcs()) {
            for (DirectedGraphArc<Resource, LinkProperty> link : instance.getResourcesGraph().getArcs()) {
                IloNumVar varIK = varMap.get(edge.getFrom()).get(link.getFrom());
                IloNumVar varJT = varMap.get(edge.getTo()).get(link.getTo());

                IloNumExpr term = cplex.prod(varIK, varJT);
                double x = link.getData().getNormalizedLatency(maxLatency);
                double cost;
                if (link.getFrom().getPlacement() == link.getTo().getPlacement()) {
                    if (link.getFrom().getPlacement() == Placement.CLOUD) {
                        cost = instance.getCost().getNormalizedCloudCloud();
                    } else {
                        cost = instance.getCost().getNormalizedEdgeEdge();
                    }
                } else {
                    cost = instance.getCost().getNormalizedCloudEdge();
                }
                x += cost * edge.getData().getBandwidth() / link.getData().getBandwidth();
                term = cplex.prod(term, x);

                communicationCost = cplex.sum(communicationCost, term);
            }
        }

        /*
        sum(i in nodes, j in nodes, k in resources, t in resources)
        x[i][k]*x[j][t]*(edgeData[i][j] * diffhost[k][t])* (latency[k][t] +
                                    e1[k][t] * edgeCloudCost * d[i][j]* invertedbandwidth[k][t]+
                                e2[k][t] * edgeCost * d[i][j]*invertedbandwidth[k][t]+
                                    e3[k][t] * cloudCost * d[i][j]*invertedbandwidth[k][t]);
         */

        cplex.addMinimize(cplex.sum(executionCost, communicationCost));
    }

    private void addCapacityConstraint() throws IloException {
        for (Resource resource : instance.getResourcesGraph().getNodes()) {
            IloNumExpr lhs = cplex.constant(0);
            for (Node node : instance.getNodesGraph().getNodes()) {
                IloNumVar var = varMap.get(node).get(resource);
                lhs = cplex.sum(lhs, cplex.prod(var, node.getConsumption()));
            }
            cplex.addLe(lhs, resource.getCapacity(), String.format("capacity_constraint(\"%s\")", resource.getLabel()));
        }

        /*
        forall(j in resources){
            capacity_constraint:
                sum(i in nodes) x[i][j]*nodesData[i].nodeCapacity <= resourcesData[j].resourceCapacity;
        }
         */
    }

    private void addAllocationConstraint() throws IloException {
        for (Node node : instance.getNodesGraph().getNodes()) {
            IloNumExpr lhs = cplex.constant(0);
            for (Resource resource : instance.getResourcesGraph().getNodes()) {
                IloNumVar var = varMap.get(node).get(resource);
                lhs = cplex.sum(lhs, var);
            }
            cplex.addEq(lhs, 1, String.format("allocation_resources(\"%s\")", node.getLabel()));
        }

        /*
        forall (i in nodes){
            allocation_resources:
                sum(j in resources) x[i][j] == 1;
        }
         */
    }

    private void addTypePlacementConstraint() throws IloException {
        for (Node node : instance.getNodesGraph().getNodes()) {
            for (Resource resource : instance.getResourcesGraph().getNodes()) {
                IloNumVar var = varMap.get(node).get(resource);
                if ((node.getType() == Type.SOURCE && resource.getPlacement() != Placement.EDGE)
                                || (node.getType() == Type.SINK && resource.getPlacement() != Placement.CLOUD)) {
                    var.setLB(0);
                    var.setUB(0);
                }
            }
        }

        /*
        //source sink
        forall(i in nodes){
          source_sink:
          forall(t in resources){
            if(nodesData[i].type == 0){ // if the node is source it must be on the edge resource
                if(resourcesData[t].placement != 1){
                 x[i][t] == 0;
               }
            }
            else if(nodesData[i].type == 1){ // if the node is sink it must be on the cloud
                if(resourcesData[t].placement != 0){
                 x[i][t] == 0;
                }
            }
          }
        }
         */
    }

    private void addVariables() throws IloException {
        for (Node i : instance.getNodesGraph().getNodes()) {
            Map<Resource, IloNumVar> resourceVariables = new HashMap<>();
            for (Resource j : instance.getResourcesGraph().getNodes()) {
                IloNumVar var = cplex.boolVar(String.format("x(\"%s\")(\"%s\")", i.getLabel(), j.getLabel()));
                resourceVariables.put(j, var);
            }
            varMap.put(i, resourceVariables);
        }
    }


}
